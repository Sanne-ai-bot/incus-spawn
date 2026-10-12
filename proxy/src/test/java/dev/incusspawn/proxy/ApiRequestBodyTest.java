package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A request to a credential-injected domain that the proxy does not translate streams upstream as it
 * arrives, with the real credential on its head, instead of being held whole in the shared proxy's
 * heap first (#1315). Held whole, a 64 MiB {@code git push} exhausted the native proxy's heap.
 */
class ApiRequestBodyTest {

    /** The Anthropic API (an API key) and GitHub (a tool proxy's basic auth). */
    static final List<String> HOSTS = List.of("api.anthropic.com", "github.com");
    /** Where a Vertex account's model calls go, for the region below. */
    static final String VERTEX_HOST = "us-east5-aiplatform.googleapis.com";

    static final String REAL_ANTHROPIC = "sk-ant-api03-real";
    static final String REAL_GITHUB = "ghp_realtoken";
    static final String REAL_OAUTH = "sk-ant-oat01-real";
    /** What each host's request must carry upstream instead of the instance's placeholder. */
    static final Map<String, String> CREDENTIAL = Map.of(
            "api.anthropic.com", "x-api-key: " + REAL_ANTHROPIC,
            "github.com", "Authorization: Basic "
                    + Base64.getEncoder().encodeToString(("x-access-token:" + REAL_GITHUB).getBytes(StandardCharsets.UTF_8)));

    @TempDir
    static Path tempHome;

    static String origHome;
    static Vertx vertx;
    static MitmProxy proxy;
    static MitmProxy oauthProxy;
    static MitmProxy vertexProxy;
    /** Model calls upstream has refused once with a 401, by path. */
    static final java.util.Set<String> refusedOnce = ConcurrentHashMap.newKeySet();
    static NetClient client;
    static final Map<String, Integer> upstreamPorts = new ConcurrentHashMap<>();
    /** What each upstream received, by host; its head is recorded as it arrives. */
    static final Map<String, CompletableFuture<Received>> received = new ConcurrentHashMap<>();
    /** Completed once a {@code /stream/} upload has brought upstream its first 10 bytes. */
    static volatile CompletableFuture<Buffer> streamStarted = new CompletableFuture<>();

    record Received(String path, Map<String, String> head, byte[] body) {
        String header(String name) {
            return head.get(name.toLowerCase());
        }
    }

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        Files.createDirectories(tempHome.resolve(".config/incus-spawn"));

        var ca = CertificateAuthority.loadOrCreate();
        vertx = Vertx.vertx();
        for (var host : java.util.stream.Stream.concat(HOSTS.stream(), java.util.stream.Stream.of(VERTEX_HOST)).toList()) {
            var server = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                            .setHandle100ContinueAutomatically(true)
                            .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, host)))
                    .requestHandler(req -> answer(host, req));
            upstreamPorts.put(host, NpmCacheProxyTest.listen(server));
        }

        var auth = new ToolDef.AuthDef();
        auth.setType("basic");
        auth.setUsername("x-access-token");
        auth.setPassword("${token}");
        var github = new ResolvedToolProxy("gh", "github.com", auth, Map.of("token", REAL_GITHUB));
        proxy = startProxy(new ProxyCredentials(REAL_ANTHROPIC, "", false, "", "", List.of(github)));
        oauthProxy = startProxy(new ProxyCredentials("", REAL_OAUTH, false, "", "", List.of()));
        vertexProxy = startProxy(new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1",
                new ProxyCredentials("", "", true, "us-east5", "proj", List.of())) {
            @Override
            String fetchGcloudToken() {
                return "ya29.fresh";
            }
        });
        vertexProxy.vertexToken.set(MitmProxy.VertexTokenEntry.resolved("ya29.stale"));

        client = vertx.createNetClient(new NetClientOptions()
                .setSsl(true).setTrustAll(true).setHostnameVerificationAlgorithm(""));
    }

    static MitmProxy startProxy(ProxyCredentials credentials) throws Exception {
        return startProxy(new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", credentials));
    }

    static MitmProxy startProxy(MitmProxy p) throws Exception {
        p.trustUpstreamCertificate(SpawnConfig.configDir().resolve("ca.crt").toString());
        ContainerTls.startInBackground(p);
        upstreamPorts.forEach((host, port) -> p.overrideUpstream(host, "127.0.0.1", port));
        return p;
    }

    static void answer(String host, HttpServerRequest req) {
        var head = new ConcurrentHashMap<String, String>();
        req.headers().forEach(e -> head.put(e.getKey().toLowerCase(), e.getValue()));
        if (req.path().startsWith("/early/")) {
            // Refuses the upload on its head, and reads the rest, as nginx does before it closes
            req.handler(ignored -> {});
            req.response().setStatusCode(413).putHeader("Connection", "close").end("too large");
            return;
        }
        if (req.path().contains("refuse-once") && refusedOnce.add(req.path())) {
            // A token Vertex no longer takes: the proxy gets a fresh one and sends the request again
            req.body().onSuccess(ignored -> req.response().setStatusCode(401).end("stale token"));
            return;
        }
        var body = Buffer.buffer();
        req.handler(chunk -> {
            body.appendBuffer(chunk);
            if (body.length() >= 10) streamStarted.complete(body.copy());
        });
        req.endHandler(v -> {
            received.computeIfAbsent(host, h -> new CompletableFuture<>())
                    .complete(new Received(req.path(), head, body.getBytes()));
            req.response().putHeader("Connection", "close").putHeader("Content-Type", "application/json")
                    .end("{\"got\":" + body.length() + "}");
        });
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (oauthProxy != null) oauthProxy.stop();
            if (vertexProxy != null) vertexProxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    /** The head a container sends: its placeholder, which the proxy replaces. */
    static String head(String method, String host, String path, String... headers) {
        var sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
        sb.append(host.equals("github.com")
                ? "Authorization: Basic " + Base64.getEncoder().encodeToString("x-access-token:gho_placeholder".getBytes(StandardCharsets.UTF_8))
                : "x-api-key: sk-ant-placeholder");
        sb.append("\r\n");
        for (var h : headers) sb.append(h).append("\r\n");
        return sb.append("Connection: close\r\n\r\n").toString();
    }

    static void assertRealCredential(String host, Received got) {
        var expected = CREDENTIAL.get(host);
        var name = expected.substring(0, expected.indexOf(':'));
        assertEquals(expected.substring(name.length() + 2), got.header(name), "credential on the upstream head");
        assertFalse(got.head().toString().contains("placeholder"), "the placeholder reached upstream: " + got.head());
    }

    /** Upstream has the first half of the upload while the client still holds the second. */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void uploadReachesUpstreamBeforeItHasAllArrived(String host) throws Exception {
        streamStarted = new CompletableFuture<>();
        received.clear();
        var socket = connect(proxy, host);
        var answer = RelayRequestBodyTest.collectUntilClose(socket);
        socket.write(head("POST", host, "/stream/upload", "Content-Length: 20") + "first half");
        try {
            assertEquals("first half", streamStarted.get(5, TimeUnit.SECONDS).getString(0, 10));
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            fail("Upstream got nothing of the upload before all of it had reached the proxy");
        }

        socket.write(", the rest");
        var reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("first half, the rest", new String(got.body(), StandardCharsets.UTF_8));
        assertRealCredential(host, got);
    }

    /** A chunked upload (git's push) reaches upstream chunked, as the client framed it. */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void chunkedUploadStaysChunked(String host) throws Exception {
        var reply = exchange(proxy, host, head("POST", host, "/o/r.git/git-receive-pack", "Transfer-Encoding: chunked")
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("hello world", new String(got.body(), StandardCharsets.UTF_8));
        assertEquals("chunked", got.header("Transfer-Encoding"));
        assertNull(got.header("Content-Length"), "re-framed with a length the proxy computed");
        assertRealCredential(host, got);
    }

    /** OAuth's bearer replaces whatever the instance sent, on a streamed request too. */
    @Test
    void oauthBearerIsInjectedOnAStreamedUpload() throws Exception {
        var host = "api.anthropic.com";
        var reply = exchange(oauthProxy, host, "POST /v1/messages HTTP/1.1\r\nHost: " + host + "\r\n"
                + "x-api-key: sk-ant-placeholder\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("Bearer " + REAL_OAUTH, got.header("Authorization"));
        assertNull(got.header("x-api-key"), "the placeholder key reached upstream");
    }

    /**
     * A client that asks {@code Expect: 100-continue} gets upstream's {@code 100}, which upstream sends
     * on a head that already carries the real credential, instead of waiting out its own timeout.
     */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void expectContinueIsAnsweredOnAHeadWithTheCredential(String host) throws Exception {
        received.clear();
        var socket = connect(proxy, host);
        var bytes = new StringBuffer();
        var continued = new CompletableFuture<Void>();
        var answer = new CompletableFuture<String>();
        socket.handler(b -> {
            bytes.append(b.toString(StandardCharsets.UTF_8));
            if (bytes.indexOf("HTTP/1.1 100 Continue\r\n\r\n") == 0) continued.complete(null);
        });
        socket.closeHandler(v -> answer.complete(bytes.toString()));
        socket.write(head("POST", host, "/v1/files", "Expect: 100-continue", "Content-Length: 5"));
        try {
            continued.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            fail("No 100 Continue for the client; got so far: '" + bytes + "'");
        }

        socket.write("hello");
        var reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.contains("HTTP/1.1 200"), reply);
        var got = received.get(host).get(5, TimeUnit.SECONDS);
        assertEquals("hello", new String(got.body(), StandardCharsets.UTF_8));
        assertRealCredential(host, got);
    }

    /** Upstream's answer to an upload it refuses early reaches the client before the upload ends. */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void earlyAnswerReachesTheClient(String host) throws Exception {
        var reply = exchange(proxy, host, head("POST", host, "/early/upload", "Content-Length: 100000")
                + "only the start of it");
        assertTrue(reply.startsWith("HTTP/1.1 413"), reply);
        assertTrue(reply.endsWith("too large"), reply);
    }

    /** A relay whose connect fails answers and closes, without reading the rest of the upload. */
    @ParameterizedTest
    @FieldSource("HOSTS")
    void failedConnectClosesWithoutReadingTheUpload(String host) throws Exception {
        // A port nothing holds, so the connect is refused (ArtifactCacheProxyTest.refused)
        int port;
        try (var closed = new java.net.Socket()) {
            closed.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            port = closed.getLocalPort();
        }
        proxy.overrideUpstream(host, "127.0.0.1", port);
        try {
            var reply = exchange(proxy, host, head("POST", host, "/upload", "Content-Length: 100000")
                    + "only the start of it");
            assertTrue(reply.startsWith("HTTP/1.1 502"), reply);
        } finally {
            proxy.overrideUpstream(host, "127.0.0.1", upstreamPorts.get(host));
            proxy.upstream.clearUnreachable();
        }
    }

    /**
     * {@code --debug} does not hold a request back to log it: the upload still streams, and the log
     * holds a bounded prefix of each body, marked when it was cut.
     */
    @Test
    void debugLogCapturesAPrefixWhileTheUploadStreams() throws Exception {
        var host = "github.com";
        var logDir = tempHome.resolve("debug");
        proxy.setDebugLog(new ApiTrafficLog(logDir));
        try {
            streamStarted = new CompletableFuture<>();
            received.clear();
            var total = ApiTrafficLog.MAX_BODY_CAPTURE + 100;
            var socket = connect(proxy, host);
            var answer = RelayRequestBodyTest.collectUntilClose(socket);
            socket.write(head("POST", host, "/stream/upload", "Content-Length: " + total) + "first half");
            try {
                streamStarted.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                socket.close();
                fail("With --debug, upstream got nothing of the upload before all of it had reached the proxy");
            }
            socket.write("x".repeat(total - 10));
            var reply = answer.get(10, TimeUnit.SECONDS);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
            assertEquals(total, received.get(host).get(5, TimeUnit.SECONDS).body().length);

            var log = waitForLog(logDir, "\"got\": " + total);
            assertTrue(log.contains("[captured " + ApiTrafficLog.MAX_BODY_CAPTURE + " of " + total + " bytes]"), log);
            assertTrue(log.contains("\"got\": " + total), "the response body is logged: " + log);
            assertFalse(log.contains(REAL_GITHUB), "the real credential is never logged");
        } finally {
            proxy.setDebugLog(null);
        }
    }

    // --- Vertex model calls: translated, so still read whole before they are sent ---

    /** A standard Messages request is translated into Vertex's rawPredict, with the GCP token. */
    @Test
    void vertexMessagesAreTranslated() throws Exception {
        var body = "{\"model\":\"claude-sonnet-4-6-20250929\",\"max_tokens\":5,\"messages\":[],"
                + "\"context_management\":{},\"stream\":true}";
        var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", "/v1/messages",
                "anthropic-beta: claude-code-20250219", "Content-Length: " + body.length()) + body);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
        assertEquals("/v1/projects/proj/locations/us-east5/publishers/anthropic/models/claude-sonnet-4-6:streamRawPredict",
                got.path());
        assertTrue(got.header("Authorization").startsWith("Bearer ya29."), got.header("Authorization"));
        assertNull(got.header("x-api-key"));
        assertNull(got.header("anthropic-beta"));
        var sent = new String(got.body(), StandardCharsets.UTF_8);
        assertEquals(String.valueOf(got.body().length), got.header("Content-Length"));
        assertTrue(sent.contains("\"anthropic_version\":\"vertex-2023-10-16\""), sent);
        assertFalse(sent.contains("model") || sent.contains("context_management"), sent);
    }

    /** A 401 from Vertex is answered by sending the same request again, once, with a fresh token. */
    @Test
    void vertex401IsRetriedWithAFreshToken() throws Exception {
        var path = "/v1/projects/proj/locations/us-east5/publishers/anthropic/models/refuse-once@20251001:rawPredict";
        var body = "{\"anthropic_version\":\"vertex-2023-10-16\",\"max_tokens\":5,\"messages\":[]}";
        var reply = exchange(vertexProxy, "api.anthropic.com", head("POST", "api.anthropic.com", path,
                "Content-Length: " + body.length()) + body);
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
        var got = received.get(VERTEX_HOST).get(5, TimeUnit.SECONDS);
        assertEquals(path.replace("@20251001", ""), got.path());
        assertEquals("Bearer ya29.fresh", got.header("Authorization"));
        assertEquals(body, new String(got.body(), StandardCharsets.UTF_8));
    }

    /** The log in {@code dir} once it holds {@code marker}: it is written after the client has its answer. */
    static String waitForLog(Path dir, String marker) throws Exception {
        var last = "";
        for (int i = 0; i < 50; i++) {
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    var file = files.findFirst();
                    if (file.isPresent()) {
                        last = Files.readString(file.get());
                        if (last.contains(marker)) return last;
                    }
                }
            }
            Thread.sleep(100);
        }
        return fail("No debug log holding '" + marker + "' was written to " + dir + "; last read: " + last);
    }

    static NetSocket connect(MitmProxy p, String host) throws Exception {
        return client.connect(p.mitmPort(), "127.0.0.1", host)
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Sends the request in one write and returns everything the proxy answers until it closes. */
    static String exchange(MitmProxy p, String host, String request) throws Exception {
        received.clear();
        var socket = connect(p, host);
        var answer = RelayRequestBodyTest.collectUntilClose(socket);
        socket.write(request);
        try {
            return answer.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            socket.close();
            return fail("The proxy did not close the connection within 10s");
        }
    }
}
