import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
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
 * </ul>
 *
 * <p>Usage: {@code java [-Dstub.apiKey=K] [-Dstub.vertexToken=T] UpstreamStub.java <port> <keystore.p12> <password> <url-path> <payload>}
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

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        char[] password = args[2].toCharArray();
        String artifactPath = args[3];
        byte[] artifact = Files.readAllBytes(Path.of(args[4]));
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(artifact));
        byte[] sha1Body = sha1.getBytes(StandardCharsets.US_ASCII);
        String apiKey = System.getProperty("stub.apiKey", "");
        String vertexBearer = "Bearer " + System.getProperty("stub.vertexToken", "");

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
                if (path.equals(artifactPath)) {
                    exchange.getResponseHeaders().set("X-Checksum-SHA1", sha1);
                    body = artifact;
                } else if (path.equals(artifactPath + ".sha1")) {
                    body = sha1Body;
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
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
        System.out.println("ready");
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
