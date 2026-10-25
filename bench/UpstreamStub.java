import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Stand-in for the upstreams of {@code bench/run.sh}'s loads, so a benchmark never sends its load
 * to a real service and measures the proxy rather than that service's latency.
 * <ul>
 *   <li>For {@code --load=maven}, Maven Central: serves one artifact, with the
 *       {@code X-Checksum-SHA1} header Central sends, and its {@code .sha1}. The proxy confirms
 *       cache hits with upstream.</li>
 *   <li>For {@code --load=anthropic} and {@code --load=vertex}, the Messages API and Vertex's
 *       {@code rawPredict}: a POST to {@code /v1/messages} or to a {@code :rawPredict} or
 *       {@code :streamRawPredict} path is read whole and answered with a canned reply, as a
 *       stream of server-sent events when it asked for one. It answers 401 unless the request
 *       carries the credential the proxy should have injected (the {@code stub.apiKey} and
 *       {@code stub.vertexToken} system properties), so a run whose injection failed shows as
 *       errors rather than as throughput.</li>
 *   <li>For {@code --load=npm}, the npm registry: the packument of {@link #NPM_PACKAGE} (with an
 *       ETag), its version document (with {@code dist.integrity}, which the proxy verifies a
 *       tarball against) and its tarball, the payload.</li>
 *   <li>For {@code --load=websocket}, OpenAI's Responses WebSocket, on a port of its own
 *       ({@code stub.wsPort}): a TLS WebSocket server that refuses (401) a handshake without
 *       {@code Authorization: Bearer <stub.openaiKey>}, and answers every message with a streamed
 *       reply, {@link #WS_DELTAS} delta events and a {@code response.completed}, as Codex reads it.</li>
 * </ul>
 *
 * <p>Usage: {@code java [-Dstub.apiKey=K] [-Dstub.vertexToken=T] [-Dstub.wsPort=P -Dstub.openaiKey=O] UpstreamStub.java <port> <keystore.p12> <password> <url-path> <payload>}
 */
public class UpstreamStub {

    private static final byte[] MESSAGE = """
            {"id":"msg_isxbench","type":"message","role":"assistant","model":"claude-sonnet-4-6",\
            "content":[{"type":"text","text":"Benchmark reply."}],"stop_reason":"end_turn",\
            "stop_sequence":null,"usage":{"input_tokens":4096,"output_tokens":4}}"""
            .getBytes(StandardCharsets.UTF_8);

    /** A short reply as the Messages API streams it: the events Claude Code reads, in order. */
    private static final byte[] STREAM = ("""
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_isxbench","type":"message","role":"assistant","model":"claude-sonnet-4-6","content":[],"stop_reason":null,"usage":{"input_tokens":4096,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            """ + """
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Benchmark reply, token by token. "}}

            """.repeat(16) + """
            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":64}}

            event: message_stop
            data: {"type":"message_stop"}

            """).getBytes(StandardCharsets.UTF_8);

    private static final Pattern STREAMED = Pattern.compile("\"stream\"\\s*:\\s*true");

    /** The one package the npm routes know: a name no registry has. */
    static final String NPM_PACKAGE = "isx-bench-payload";
    static final String NPM_VERSION = "1.0.0";
    static final String NPM_TARBALL = "/" + NPM_PACKAGE + "/-/" + NPM_PACKAGE + "-" + NPM_VERSION + ".tgz";

    /** Delta events per streamed WebSocket reply. */
    static final int WS_DELTAS = 20;
    private static final byte[] WS_DELTA = """
            {"type":"response.output_text.delta","item_id":"msg_isxbench","output_index":0,"content_index":0,"delta":"Benchmark reply, token by token. "}"""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] WS_COMPLETED = """
            {"type":"response.completed","response":{"id":"resp_isxbench","status":"completed","usage":{"input_tokens":4096,"output_tokens":64}}}"""
            .getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        char[] password = args[2].toCharArray();
        String artifactPath = args[3];
        byte[] artifact = Files.readAllBytes(Path.of(args[4]));
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(artifact));
        byte[] sha1Body = sha1.getBytes(StandardCharsets.US_ASCII);
        String apiKey = System.getProperty("stub.apiKey", "");
        String vertexBearer = "Bearer " + System.getProperty("stub.vertexToken", "");
        byte[] integrity = MessageDigest.getInstance("SHA-512").digest(artifact);
        String npmDist = "{\"tarball\":\"https://registry.npmjs.org" + NPM_TARBALL + "\",\"shasum\":\"" + sha1
                + "\",\"integrity\":\"sha512-" + Base64.getEncoder().encodeToString(integrity) + "\"}";
        String npmVersionDoc = "{\"name\":\"" + NPM_PACKAGE + "\",\"version\":\"" + NPM_VERSION + "\",\"dist\":" + npmDist + "}";
        byte[] npmVersion = npmVersionDoc.getBytes(StandardCharsets.UTF_8);
        byte[] npmPackument = ("{\"name\":\"" + NPM_PACKAGE + "\",\"dist-tags\":{\"latest\":\"" + NPM_VERSION
                + "\"},\"versions\":{\"" + NPM_VERSION + "\":" + npmVersionDoc + "}}").getBytes(StandardCharsets.UTF_8);

        var keyStore = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(args[1])) {
            keyStore.load(in, password);
        }
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        var tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);

        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 1024);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            try (exchange) {
                var path = exchange.getRequestURI().getRawPath();
                if ("POST".equals(exchange.getRequestMethod())) {
                    if (path.equals("/v1/messages")) {
                        answerModel(exchange, apiKey.equals(exchange.getRequestHeaders().getFirst("x-api-key")), null);
                    } else if (path.endsWith(":rawPredict") || path.endsWith(":streamRawPredict")) {
                        answerModel(exchange, vertexBearer.equals(exchange.getRequestHeaders().getFirst("Authorization")),
                                path.endsWith(":streamRawPredict"));
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                    return;
                }
                byte[] body;
                var type = "application/octet-stream";
                if (path.equals(artifactPath) || path.equals(NPM_TARBALL)) {
                    exchange.getResponseHeaders().set("X-Checksum-SHA1", sha1);
                    body = artifact;
                } else if (path.equals(artifactPath + ".sha1")) {
                    body = sha1Body;
                } else if (path.equals("/" + NPM_PACKAGE)) {
                    exchange.getResponseHeaders().set("ETag", "\"isx-bench-1\"");
                    body = npmPackument;
                    type = "application/json";
                } else if (path.equals("/" + NPM_PACKAGE + "/" + NPM_VERSION)) {
                    body = npmVersion;
                    type = "application/json";
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        var wsPort = Integer.getInteger("stub.wsPort", 0);
        if (wsPort > 0) {
            var wsBearer = "Bearer " + System.getProperty("stub.openaiKey", "");
            var wsServer = tls.getServerSocketFactory().createServerSocket(wsPort, 1024, InetAddress.getLoopbackAddress());
            Thread.ofPlatform().daemon().start(() -> {
                while (true) {
                    try {
                        var socket = wsServer.accept();
                        Thread.ofVirtual().start(() -> serveWebSocket(socket, wsBearer));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }
        System.out.println("ready");
    }

    /**
     * One WebSocket connection, by RFC 6455 and no more than the relay sends: the handshake, then
     * every text or binary message answered with a streamed reply, pings with pongs, a close with a
     * close. Frames from the client are masked; ours are not.
     */
    private static void serveWebSocket(Socket socket, String bearer) {
        try (socket; var in = new java.io.BufferedInputStream(socket.getInputStream());
             var out = new java.io.BufferedOutputStream(socket.getOutputStream())) {
            var head = new StringBuilder();
            int matched = 0;  // how much of the blank line ending the head has been read
            while (matched < 4) {
                int b = in.read();
                if (b < 0 || head.length() > 65536) return;
                head.append((char) b);
                matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            String key = null, auth = null;
            for (var line : head.toString().split("\r\n")) {
                var colon = line.indexOf(':');
                if (colon < 0) continue;
                var name = line.substring(0, colon).trim();
                var value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Sec-WebSocket-Key")) key = value;
                if (name.equalsIgnoreCase("Authorization")) auth = value;
            }
            if (key == null || !bearer.equals(auth)) {
                out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            var accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            var message = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) return;
                int opcode = b0 & 0x0f;
                long length = b1 & 0x7f;
                var data = new java.io.DataInputStream(in);
                if (length == 126) length = data.readUnsignedShort();
                else if (length == 127) length = data.readLong();
                if (length < 0 || length > 16 << 20) return;  // more than the relay ever sends
                var mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : null;
                var payload = in.readNBytes((int) length);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                switch (opcode) {
                    case 0x8 -> { writeFrame(out, 0x8, payload); out.flush(); return; }
                    case 0x9 -> { writeFrame(out, 0xA, payload); out.flush(); }
                    case 0xA -> { }
                    default -> {
                        message.write(payload);
                        if ((b0 & 0x80) != 0) {
                            message.reset();
                            for (int i = 0; i < WS_DELTAS; i++) writeFrame(out, 0x1, WS_DELTA);
                            writeFrame(out, 0x1, WS_COMPLETED);
                            out.flush();
                        }
                    }
                }
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            // the client went away
        }
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length < 65536) {
            out.write(126);
            out.write(payload.length >> 8);
            out.write(payload.length & 0xff);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((int) (((long) payload.length >> (8 * i)) & 0xff));
        }
        out.write(payload);
    }

    /**
     * A model call: the whole body is read, as the real API reads it, then answered. {@code stream}
     * is null when the body decides it (the Messages API), or what the Vertex path asked for.
     */
    private static void answerModel(HttpExchange exchange, boolean authorized, Boolean stream) throws IOException {
        var request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!authorized) {
            exchange.sendResponseHeaders(401, -1);
            return;
        }
        boolean streamed = stream != null ? stream : STREAMED.matcher(request).find();
        var body = streamed ? STREAM : MESSAGE;
        exchange.getResponseHeaders().set("Content-Type", streamed ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }
}
