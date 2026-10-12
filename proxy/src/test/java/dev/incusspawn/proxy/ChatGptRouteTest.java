package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.WebSocketConnectOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy side of host-owned ChatGPT sessions (#1332), against a stub of OpenAI's token
 * endpoint and the Codex backend: what a claimed request must prove, what it may do, and what a
 * refresh may write. The stub records every request, so "never relayed" is checked, not assumed.
 */
class ChatGptRouteTest {

    private static final String ACCOUNT = "sso";
    private static final String INSTANCE = "worker";
    private static final String SECRET = "11".repeat(32);
    private static final String OTHER_SECRET = "22".repeat(32);
    private static final String ACCOUNT_ID = "acct-test";

    @TempDir
    static Path home;
    static String originalHome;
    static Vertx vertx;
    static HttpServer stub;
    static MitmProxy proxy;
    static int mitmPort;
    static ChatGptSessionStore store;
    static volatile String sessionId;

    /** Every request the stub saw: "METHOD host path | authorization | account". */
    static final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> refreshBodies = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> revokeBodies = new ConcurrentLinkedQueue<>();
    static final AtomicInteger refreshes = new AtomicInteger();
    static volatile String refreshError;
    static volatile int refreshErrorStatus = 400;
    static final ConcurrentLinkedQueue<String> refreshEncodings = new ConcurrentLinkedQueue<>();
    static volatile CompletableFuture<Void> holdRefresh = CompletableFuture.completedFuture(null);
    static volatile boolean backendRefuses;
    static volatile long mintedTtlSeconds = 3600;

    @BeforeAll
    static void start() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        var configDir = Files.createDirectories(home.resolve(".config/incus-spawn"));
        Files.writeString(configDir.resolve("config.yaml"), """
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                    keyed:
                      apiKey: "sk-real-api-key"
                  default: keyed
                """);
        store = new ChatGptSessionStore();

        // Both names resolve to loopback, where nothing listens on 443: a dial that ignored the
        // override would fail here rather than leave the machine
        vertx = Vertx.vertx(new io.vertx.core.VertxOptions().setAddressResolverOptions(
                new io.vertx.core.dns.AddressResolverOptions().setHostsValue(
                        Buffer.buffer("127.0.0.1 chatgpt.com auth.openai.com\n"))));
        var ca = CertificateAuthority.loadOrCreate();
        stub = vertx.createHttpServer(new HttpServerOptions().setSsl(true)
                        .setKeyCertOptions(NpmCacheProxyTest.keyCert(ca, ChatGptRoute.CHATGPT)))
                .requestHandler(ChatGptRouteTest::answer)
                .webSocketHandler(ws -> {
                    seen.add("WS " + ws.headers().get("Host") + " " + ws.path() + " | "
                            + ws.headers().get("Authorization") + " | " + ws.headers().get("ChatGPT-Account-Id"));
                    ws.textMessageHandler(msg -> ws.writeTextMessage("echo:" + msg));
                });
        int stubPort = NpmCacheProxyTest.listen(stub);

        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        proxy.upstream.upstreamTrustAll = true;
        proxy.overrideUpstream(ChatGptRoute.CHATGPT, "127.0.0.1", stubPort);
        proxy.overrideUpstream(ChatGptRoute.AUTH, "127.0.0.1", stubPort);
        ContainerTls.startInBackground(proxy);
        mitmPort = proxy.mitmPort();
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    /** A fresh session for every test, and the worker instance bound to it with SECRET's proofs. */
    @BeforeEach
    void freshSession() {
        seen.clear();
        refreshBodies.clear();
        revokeBodies.clear();
        refreshes.set(0);
        refreshError = null;
        refreshErrorStatus = 400;
        refreshEncodings.clear();
        holdRefresh = CompletableFuture.completedFuture(null);
        backendRefuses = false;
        mintedTtlSeconds = 3600;
        proxy.clearAuthError();
        sessionId = store.create(ACCOUNT, mint(3600)).sessionId();
        bind(SECRET, sessionId);
    }

    // --- Fixtures ---

    static ChatGptSessionStore.Tokens mint(long ttlSeconds) {
        var now = System.currentTimeMillis() / 1000;
        var auth = "{\"chatgpt_plan_type\":\"pro\",\"chatgpt_account_id\":\"" + ACCOUNT_ID
                + "\",\"organizations\":[\"org-secret-detail\"]}";
        var access = jwt("{\"exp\":" + (now + ttlSeconds) + ",\"jti\":\"" + java.util.UUID.randomUUID()
                + "\",\"https://api.openai.com/auth\":" + auth + "}");
        var id = jwt("{\"exp\":" + (now + 3600) + ",\"email\":\"user@example.invalid\",\"https://api.openai.com/auth\":" + auth + "}");
        return new ChatGptSessionStore.Tokens(id, access, "rt.real-" + java.util.UUID.randomUUID(), ACCOUNT_ID);
    }

    static String jwt(String payload) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "."
                + enc.encodeToString(payload.getBytes()) + "." + enc.encodeToString(("real-signature-" + payload.hashCode()).getBytes());
    }

    /** Lists the worker at the test client's address, bound as a start with {@code secret} would. */
    static void bind(String secret, String session) {
        bind(secret, session, secret);
    }

    /**
     * The same, but with {@code currentSecret} as the instance's secret now: another one than the
     * proofs were made with is what a copy, or a start that did not renew the binding, looks like.
     */
    static void bind(String secret, String session, String currentSecret) {
        var proofs = ChatGptProof.derive(secret, ACCOUNT, session);
        var listing = """
                [{"name":"%s","created_at":"2026-10-01T00:00:00Z","config":{
                  "user.incus-spawn.static-ip":"127.0.0.1",
                  "user.incus-spawn.account.openai":"%s",
                  "user.incus-spawn.chatgpt-session":"%s:%s",
                  "user.incus-spawn.chatgpt-access-proof-sha256":"%s",
                  "user.incus-spawn.chatgpt-refresh-proof-sha256":"%s",
                  "user.incus-spawn.chatgpt-proof-secret-sha256":"%s",
                  "user.incus-spawn.instance-secret-sha256":"%s"}}]
                """.formatted(INSTANCE, ACCOUNT, ACCOUNT, session, ChatGptProof.verifier(proofs.access()),
                ChatGptProof.verifier(proofs.refresh()), InstanceSecret.sha256(secret), InstanceSecret.sha256(currentSecret));
        var registry = new InstanceRegistry(new IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return JqValues.parse(listing);
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);
    }

    static ChatGptProof.Proofs proofs() {
        return ChatGptProof.derive(SECRET, ACCOUNT, sessionId);
    }

    static String accessPlaceholder(String digest) {
        return ChatGptPlaceholders.accessToken(mint(3600).accessToken(), digest);
    }

    static String refreshBody(String refreshToken) {
        return "{\"client_id\":\"" + ChatGptRoute.CODEX_CLIENT_ID + "\",\"grant_type\":\"refresh_token\",\"refresh_token\":\""
                + refreshToken + "\"}";
    }

    // --- The stub ---

    static void answer(HttpServerRequest req) {
        req.body().onSuccess(body -> {
            var host = req.getHeader("Host");
            seen.add(req.method() + " " + host + " " + req.uri() + " | " + req.getHeader("Authorization")
                    + " | " + req.getHeader("ChatGPT-Account-Id"));
            var resp = req.response().putHeader("Content-Type", "application/json");
            switch (req.path()) {
                case "/oauth/token" -> {
                    refreshes.incrementAndGet();
                    refreshBodies.add(body.toString());
                    refreshEncodings.add(String.valueOf(req.getHeader("Accept-Encoding")));
                    holdRefresh.whenComplete((v, e) -> vertx.runOnContext(x -> {
                        if (refreshError != null) {
                            resp.setStatusCode(refreshErrorStatus).end("{\"error\":{\"code\":\"" + refreshError + "\"}}");
                        } else {
                            var fresh = mint(mintedTtlSeconds);
                            resp.end("{\"id_token\":\"" + fresh.idToken() + "\",\"access_token\":\"" + fresh.accessToken()
                                    + "\",\"refresh_token\":\"" + fresh.refreshToken() + "\"}");
                        }
                    }));
                }
                case "/oauth/revoke" -> {
                    revokeBodies.add(body.toString());
                    resp.end("{}");
                }
                case "/backend-api/codex/models" -> {
                    if ("client_version=redirect".equals(req.query())) {
                        resp.setStatusCode(302).putHeader("Location", "https://chatgpt.com/backend-api/wham/accounts/check").end();
                    } else {
                        resp.setStatusCode(backendRefuses ? 401 : 200).end("{\"models\":[]}");
                    }
                }
                default -> resp.setStatusCode(backendRefuses ? 401 : 200).end("{\"ok\":true}");
            }
        });
    }

    // --- A container's client: raw HTTP/1.1 over TLS, so every byte of the request is the test's ---

    record Reply(int status, String body) {}

    static Reply send(String host, String sni, String rawHead, String body) throws Exception {
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        try (var socket = (SSLSocket) tls.getSocketFactory().createSocket("127.0.0.1", mitmPort)) {
            socket.setSoTimeout(15_000);
            var params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(sni)));
            socket.setSSLParameters(params);
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            var request = rawHead + (rawHead.contains("Content-Length") || rawHead.contains("Transfer-Encoding") || bytes.length == 0 ? "" : "Content-Length: " + bytes.length + "\r\n")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            var text = readResponse(socket.getInputStream());
            var status = Integer.parseInt(text.substring(9, 12));
            var split = text.indexOf("\r\n\r\n");
            var head = text.substring(0, split);
            var payload = text.substring(split + 4);
            if (head.toLowerCase().contains("transfer-encoding: chunked")) payload = dechunk(payload);
            return new Reply(status, payload);
        }
    }

    /**
     * One response, read to its end by its own framing rather than to EOF: a request refused before
     * its body was read is answered while the client could still be sending, and the connection
     * then stays open until it stops.
     */
    static String readResponse(java.io.InputStream in) throws java.io.IOException {
        var out = new ByteArrayOutputStream();
        int headEnd = -1;
        while (true) {
            int b = in.read();
            if (b < 0) return out.toString(StandardCharsets.UTF_8);
            out.write(b);
            var text = out.toString(StandardCharsets.ISO_8859_1);
            if (headEnd < 0 && text.endsWith("\r\n\r\n")) {
                headEnd = text.length();
                var head = text.toLowerCase(java.util.Locale.ROOT);
                var cl = java.util.regex.Pattern.compile("content-length: *(\\d+)").matcher(head);
                if (cl.find()) {
                    out.write(in.readNBytes(Integer.parseInt(cl.group(1))));
                    return out.toString(StandardCharsets.UTF_8);
                }
                if (!head.contains("transfer-encoding: chunked")) {
                    out.write(in.readAllBytes());
                    return out.toString(StandardCharsets.UTF_8);
                }
            } else if (headEnd >= 0 && text.endsWith("\r\n0\r\n\r\n")) {
                return out.toString(StandardCharsets.UTF_8);
            }
        }
    }

    static String dechunk(String chunked) {
        var out = new StringBuilder();
        var rest = chunked;
        while (true) {
            var eol = rest.indexOf("\r\n");
            var size = Integer.parseInt(rest.substring(0, eol).trim(), 16);
            if (size == 0) return out.toString();
            out.append(rest, eol + 2, eol + 2 + size);
            rest = rest.substring(eol + 2 + size + 2);
        }
    }

    static Reply backend(String method, String target, String bearer) throws Exception {
        return send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, method + " " + target + " HTTP/1.1\r\nHost: chatgpt.com\r\n"
                + (bearer == null ? "" : "Authorization: Bearer " + bearer + "\r\n") + "ChatGPT-Account-Id: guest-says\r\n",
                method.equals("POST") ? "{}" : "");
    }

    static Reply refresh(String refreshToken) throws Exception {
        return refreshWithBody(refreshBody(refreshToken));
    }

    static Reply refreshWithBody(String body) throws Exception {
        return send(ChatGptRoute.AUTH, ChatGptRoute.AUTH, "POST /oauth/token HTTP/1.1\r\nHost: auth.openai.com\r\n"
                + "Content-Type: application/json\r\nOriginator: codex_cli_rs\r\nAccept-Encoding: gzip, br\r\n", body);
    }

    static List<String> seenOn(String path) {
        return seen.stream().filter(s -> s.contains(" " + path)).toList();
    }

    // --- Injection ---

    @Test
    void anAllowedRequestWithTheCurrentProofCarriesTheRealTokenAndNothingOfTheGuests() throws Exception {
        var reply = backend("POST", "/backend-api/codex/responses", accessPlaceholder(proofs().access()));

        assertEquals(200, reply.status(), reply.body());
        var upstream = seenOn("/backend-api/codex/responses");
        assertEquals(1, upstream.size(), seen.toString());
        assertTrue(upstream.getFirst().contains("Bearer " + store.read(ACCOUNT).tokens().accessToken()), upstream.toString());
        assertTrue(upstream.getFirst().endsWith("| " + ACCOUNT_ID), "the account id is the session's, not the guest's: " + upstream);
        assertFalse(upstream.getFirst().contains(ChatGptProof.MARKER), "no placeholder goes upstream: " + upstream);
    }

    @Test
    void theModelsListTakesOnlyTheClientVersionQuery() throws Exception {
        var bearer = accessPlaceholder(proofs().access());
        assertEquals(200, backend("GET", "/backend-api/codex/models?client_version=0.162.1", bearer).status());
        assertEquals(403, backend("GET", "/backend-api/codex/models?client_version=0.162.1&x=1", bearer).status());
        assertEquals(1, seenOn("/backend-api/codex/models").size(), seen.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /backend-api/wham/accounts/check", "POST /backend-api/codex/analytics-events/events",
            "GET /backend-api/wham/usage", "GET /backend-api/conversations", "POST /backend-api/ps/mcp",
            "GET /backend-api/codex/responses", "DELETE /backend-api/codex/responses"})
    void anythingOffTheAllowlistIsRefusedHereWithAValidProof(String request) throws Exception {
        var parts = request.split(" ");
        var reply = backend(parts[0], parts[1], accessPlaceholder(proofs().access()));

        assertEquals(403, reply.status(), reply.body());
        assertTrue(reply.body().contains("not allowed for ChatGPT sessions"), reply.body());
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @Test
    void aRequestThatClaimsNothingIsRelayedAsItCame() throws Exception {
        var reply = backend("GET", "/backend-api/wham/accounts/check", "a-users-own-token");

        assertEquals(200, reply.status());
        assertEquals(List.of("GET chatgpt.com /backend-api/wham/accounts/check | Bearer a-users-own-token | guest-says"),
                List.copyOf(seen));
    }

    @Test
    void anUpstreamRedirectIsHandedBackNotFollowed() throws Exception {
        var reply = backend("GET", "/backend-api/codex/models?client_version=redirect", accessPlaceholder(proofs().access()));

        assertEquals(302, reply.status());
        assertEquals(1, seen.size(), "the proxy followed nothing: " + seen);
    }

    // --- What a claimed request must prove ---

    /** Every way to hold an access placeholder that is not the worker's current one. */
    static List<String> wrongAccessProofs() {
        var current = proofs();
        var forgedFromHash = ChatGptProof.derive(InstanceSecret.sha256(SECRET).substring(0, 64), ACCOUNT, sessionId);
        return List.of(
                "x.y." + ChatGptProof.MARKER + "placeholder",
                "x.y." + ChatGptProof.MARKER,
                accessPlaceholder(ChatGptProof.derive(OTHER_SECRET, ACCOUNT, sessionId).access()),
                accessPlaceholder(ChatGptProof.derive(SECRET, ACCOUNT, "previous-session").access()),
                accessPlaceholder(ChatGptProof.derive(SECRET, "keyed", sessionId).access()),
                accessPlaceholder(current.refresh()),
                accessPlaceholder(forgedFromHash.access()),
                accessPlaceholder(ChatGptProof.verifier(current.access())));
    }

    @Test
    void noAccessPlaceholderButTheCurrentOneIsServed() throws Exception {
        for (var bearer : wrongAccessProofs()) {
            var reply = backend("POST", "/backend-api/codex/responses", bearer);
            assertEquals(401, reply.status(), bearer + ": " + reply.body());
        }
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @Test
    void noRefreshPlaceholderButTheCurrentOneIsServed() throws Exception {
        var current = proofs();
        var forgedFromHash = ChatGptProof.derive(InstanceSecret.sha256(SECRET).substring(0, 64), ACCOUNT, sessionId);
        for (var token : List.of(
                ChatGptProof.REFRESH_PREFIX + "placeholder",
                ChatGptProof.derive(OTHER_SECRET, ACCOUNT, sessionId).refreshToken(),
                ChatGptProof.derive(SECRET, ACCOUNT, "previous-session").refreshToken(),
                ChatGptProof.REFRESH_PREFIX + current.access(),
                forgedFromHash.refreshToken(),
                ChatGptProof.REFRESH_PREFIX + ChatGptProof.verifier(current.refresh()))) {
            var reply = refresh(token);
            assertEquals(401, reply.status(), token + ": " + reply.body());
            assertTrue(reply.body().contains("invalid_grant"), reply.body());
            assertFalse(reply.body().contains("access_token"), "no placeholder handed out: " + reply.body());
        }
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @Test
    void aCopyWithItsSourcesVerifiersButASecretOfItsOwnGetsNothing() throws Exception {
        // The copy holds its source's guest files, placeholders included, and its metadata
        bind(SECRET, sessionId, OTHER_SECRET);

        assertEquals(401, backend("POST", "/backend-api/codex/responses", accessPlaceholder(proofs().access())).status());
        assertEquals(401, refresh(proofs().refreshToken()).status());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void anInstanceWithoutABindingGetsNothing() throws Exception {
        var proofs = proofs();
        var registry = new InstanceRegistry(new IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return JqValues.parse("[{\"name\":\"other\",\"config\":{\"user.incus-spawn.static-ip\":\"127.0.0.1\"}}]");
            }
        });
        registry.refresh();
        proxy.useInstanceRegistry(registry);

        assertEquals(401, backend("POST", "/backend-api/codex/responses", accessPlaceholder(proofs.access())).status());
        assertEquals(401, refresh(proofs.refreshToken()).status());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    // --- Shape: what upstream sees is what the allowlist matched ---

    @ParameterizedTest
    @ValueSource(strings = {
            "/backend-api/codex/responses%2f..%2fwham%2faccounts%2fcheck",
            "/backend-api/codex/%72esponses",
            "/backend-api/codex/../wham/accounts/check",
            "/backend-api/codex/./responses",
            "/backend-api//codex/responses",
            "/backend-api/codex/responses;x=1",
            "/backend-api/codex/responses/",
            "https://chatgpt.com/backend-api/codex/responses",
            "/backend-api/codex/responses#frag"})
    void aTargetThatCouldReadAsAnotherPathIsRefused(String target) throws Exception {
        var reply = backend("POST", target, accessPlaceholder(proofs().access()));

        assertTrue(reply.status() == 400 || reply.status() == 403, target + " -> " + reply.status() + " " + reply.body());
        assertTrue(seen.isEmpty(), "never relayed: " + seen);
    }

    @Test
    void duplicateCredentialHeadersAreRefused() throws Exception {
        var bearer = accessPlaceholder(proofs().access());
        var head = "POST /backend-api/codex/responses HTTP/1.1\r\nHost: chatgpt.com\r\n";
        for (var extra : List.of(
                "Authorization: Bearer " + bearer + "\r\nAuthorization: Bearer other\r\n",
                "Authorization: Bearer " + bearer + "\r\nChatGPT-Account-Id: a\r\nChatGPT-Account-Id: b\r\n",
                "Authorization: Bearer " + bearer + "\r\nChatGPT-Account-Id: a, b\r\n")) {
            var reply = send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, head + extra, "{}");
            assertEquals(400, reply.status(), extra + reply.body());
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aHostThatIsNotTheTlsNameIsRefused() throws Exception {
        var reply = send(ChatGptRoute.CHATGPT, ChatGptRoute.AUTH, "POST /backend-api/codex/responses HTTP/1.1\r\n"
                + "Host: chatgpt.com\r\nAuthorization: Bearer " + accessPlaceholder(proofs().access()) + "\r\n", "{}");

        assertEquals(400, reply.status(), reply.body());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    // --- Refresh ---

    @Test
    void aRefreshWhileTheRealTokenIsFreshIsAnsweredHereWithTheCallersOwnProofs() throws Exception {
        var proofs = proofs();
        var real = store.read(ACCOUNT).tokens();
        var reply = refresh(proofs.refreshToken());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(0, refreshes.get(), "nothing sent upstream");
        var tokens = JqValues.parseStrict(reply.body());
        assertEquals(proofs.refreshToken(), tokens.pathText("refresh_token", ""));
        assertEquals(proofs.access(), ChatGptProof.accessDigestOf(tokens.pathText("access_token", "")));
        assertEquals(ChatGptPlaceholders.expiry(real.accessToken()), ChatGptPlaceholders.expiry(tokens.pathText("access_token", "")));
        assertNoRealToken(reply.body(), real);
        assertFalse(reply.body().contains("org-secret-detail"), "only the claims Codex reads: " + reply.body());
    }

    @Test
    void aRefreshNearExpiryIsRelayedOnceWithTheRealTokenAndCommitted() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        var before = store.read(ACCOUNT);

        var reply = refresh(proofs().refreshToken());

        assertEquals(200, reply.status(), reply.body());
        assertEquals(1, refreshes.get());
        var sent = JqValues.parseStrict(refreshBodies.peek());
        assertEquals(before.tokens().refreshToken(), sent.pathText("refresh_token", ""));
        assertEquals(ChatGptRoute.CODEX_CLIENT_ID, sent.pathText("client_id", ""));
        var after = store.read(ACCOUNT);
        assertEquals(before.generation() + 1, after.generation());
        assertEquals(sessionId, after.sessionId());
        assertFalse(after.tokens().refreshToken().equals(before.tokens().refreshToken()), "rotated");
        assertNull(after.refreshing(), "the journal entry is cleared on commit");
        assertNoRealToken(reply.body(), after.tokens());
        assertTrue(seenOn("/oauth/token").getFirst().contains("| null |"), "no Authorization upstream: " + seen);
        assertEquals(List.of("identity"), List.copyOf(refreshEncodings), "an answer the proxy can parse");
    }

    @Test
    void refreshesInARowGoUpstreamOnlyOncePerInterval() throws Exception {
        // A real token that stays inside the refresh window would otherwise make every guest
        // request a refresh at OpenAI: found when the stub's tokens lived 4 minutes, and real
        // Codex refreshed nearly 6000 times in 5 minutes
        mintedTtlSeconds = 60;
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);

        for (int i = 0; i < 5; i++) assertEquals(200, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get());
    }

    @Test
    void concurrentRefreshesOfOneSessionShareOneUpstreamCall() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        holdRefresh = new CompletableFuture<>();
        var token = proofs().refreshToken();
        try (var pool = Executors.newFixedThreadPool(20)) {
            var replies = new ArrayList<java.util.concurrent.Future<Reply>>();
            for (int i = 0; i < 20; i++) replies.add(pool.submit(() -> refresh(token)));
            awaitRefreshes(1);
            holdRefresh.complete(null);
            for (var reply : replies) assertEquals(200, reply.get(20, TimeUnit.SECONDS).status());
        }
        assertEquals(1, refreshes.get());
    }

    @Test
    void aBackend401LetsTheNextRefreshThrough() throws Exception {
        backendRefuses = true;
        assertEquals(401, backend("POST", "/backend-api/codex/responses", accessPlaceholder(proofs().access())).status());
        backendRefuses = false;

        assertEquals(200, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get(), "the real token was refreshed although it was not near expiry");
        assertEquals(200, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get(), "and only once");
    }

    @Test
    void aSignInAgainDuringARefreshFailsItAndLeavesTheNewSessionAlone() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        var oldToken = proofs().refreshToken();
        holdRefresh = new CompletableFuture<>();
        var pending = CompletableFuture.supplyAsync(() -> uncheck(() -> refresh(oldToken)));
        awaitRefreshes(1);

        var newSession = store.create(ACCOUNT, mint(3600));
        holdRefresh.complete(null);
        var reply = pending.get(20, TimeUnit.SECONDS);

        assertEquals(401, reply.status(), reply.body());
        assertFalse(reply.body().contains("access_token"), "no new-session proof or placeholder: " + reply.body());
        var now = store.read(ACCOUNT);
        assertEquals(newSession, now, "the new session is untouched");
        awaitRevokes(1);
        assertTrue(revokeBodies.peek().contains("rt.real-"), "the discarded chain's token is revoked: " + revokeBodies);
        // The old session's proofs never become the new session's
        assertEquals(401, refresh(oldToken).status());
    }

    @Test
    void aRemovalDuringARefreshIsNotUndone() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        holdRefresh = new CompletableFuture<>();
        var pending = CompletableFuture.supplyAsync(() -> uncheck(() -> refresh(proofs().refreshToken())));
        awaitRefreshes(1);

        store.remove(ACCOUNT);
        holdRefresh.complete(null);

        assertEquals(401, pending.get(20, TimeUnit.SECONDS).status());
        assertNull(store.read(ACCOUNT), "the proxy never recreates a session");
        awaitRevokes(1);
    }

    @Test
    void aRefusedRefreshIsReportedAndClassifiedAsOpenAiWouldHave() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        refreshError = "refresh_token_reused";

        var reply = refresh(proofs().refreshToken());

        assertEquals(400, reply.status());
        assertTrue(reply.body().contains("\"code\":\"refresh_token_reused\""), reply.body());
        assertNotNull(proxy.authError, "the host is told to sign in again");
        assertNull(store.read(ACCOUNT).refreshing(), "the journal entry is cleared");
    }

    @Test
    void aRefreshOvertakenWithinTheSameSessionIsAnsweredFromIt() throws Exception {
        sessionId = store.create(ACCOUNT, mint(60)).sessionId();
        bind(SECRET, sessionId);
        holdRefresh = new CompletableFuture<>();
        var pending = CompletableFuture.supplyAsync(() -> uncheck(() -> refresh(proofs().refreshToken())));
        awaitRefreshes(1);
        // Another writer commits the same session's next generation first
        var current = store.read(ACCOUNT);
        assertEquals(ChatGptSessionStore.Commit.COMMITTED, store.commit(current, mint(3600)));
        holdRefresh.complete(null);

        var reply = pending.get(20, TimeUnit.SECONDS);
        assertEquals(200, reply.status(), "the session is healthy: " + reply.body());
        assertEquals(proofs().refreshToken(), JqValues.parseStrict(reply.body()).pathText("refresh_token", ""));
        assertEquals(store.read(ACCOUNT).generation(), current.generation() + 1, "the other write stands");
        awaitRevokes(1);
    }

    @Test
    void aServerErrorOnRefreshIsTransientNotASignOut() throws Exception {
        sessionId = store.create(ACCOUNT, mint(-10)).sessionId();
        bind(SECRET, sessionId);
        refreshError = "upstream_down";
        refreshErrorStatus = 503;

        var reply = refresh(proofs().refreshToken());
        assertEquals(502, reply.status(), reply.body());
        assertTrue(reply.body().contains("server_error"), reply.body());
        assertNull(proxy.authError, "nothing tells the user to sign in again");
        // Within the interval, an expired token is not handed out as if refreshed: try again later
        assertEquals(503, refresh(proofs().refreshToken()).status());
        assertEquals(1, refreshes.get());
    }

    @Test
    void aTokenRequestTooLargeToBeIsxsIsRelayedUninspected() throws Exception {
        var padding = "x".repeat(70 * 1024);
        var body = "{\"refresh_token\":\"" + proofs().refreshToken() + "\",\"pad\":\"" + padding + "\"}";
        assertEquals(200, refreshWithBody(body).status());
        assertEquals(List.of(body), List.copyOf(refreshBodies), "untouched, and nothing injected");
    }

    @Test
    void aTokenRequestIsxDoesNotRecogniseFailsClosed() throws Exception {
        var token = proofs().refreshToken();
        for (var body : List.of(
                "{\"client_id\":\"another-client\",\"grant_type\":\"refresh_token\",\"refresh_token\":\"" + token + "\"}",
                "{\"client_id\":\"" + ChatGptRoute.CODEX_CLIENT_ID + "\",\"grant_type\":\"refresh_token\",\"refresh_token\":\""
                        + token + "\",\"scope\":\"openid\"}",
                "{\"client_id\":\"" + ChatGptRoute.CODEX_CLIENT_ID + "\",\"grant_type\":\"urn:ietf:params:oauth:grant-type:token-exchange\",\"refresh_token\":\"" + token + "\"}",
                "grant_type=refresh_token&refresh_token=" + token)) {
            assertEquals(400, refreshWithBody(body).status(), body);
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aGuestLogoutNeverReachesOpenAi() throws Exception {
        var reply = send(ChatGptRoute.AUTH, ChatGptRoute.AUTH, "POST /oauth/revoke HTTP/1.1\r\nHost: auth.openai.com\r\n",
                "{\"token\":\"" + proofs().refreshToken() + "\",\"token_type_hint\":\"refresh_token\"}");

        assertEquals(200, reply.status());
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aTokenRequestWithoutAPlaceholderIsRelayedAsItCame() throws Exception {
        var body = refreshBody("rt.a-users-own");
        assertEquals(200, refreshWithBody(body).status());
        assertEquals(List.of(body), List.copyOf(refreshBodies));
    }

    // --- WebSocket ---

    @Test
    void aWebSocketWithTheCurrentProofIsDialledToTheOverrideWithTheRealToken() throws Exception {
        var client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
        var options = new WebSocketConnectOptions().setHost(ChatGptRoute.CHATGPT).setPort(mitmPort).setSsl(true)
                .setURI("/backend-api/codex/responses")
                // As a container sends it: it connects to 443, which the bridge redirects here
                .putHeader("Host", ChatGptRoute.CHATGPT)
                .addHeader("Authorization", "Bearer " + accessPlaceholder(proofs().access()));
        var echoed = new CompletableFuture<String>();
        client.webSocket(options).onSuccess(ws -> {
            ws.textMessageHandler(echoed::complete);
            ws.writeTextMessage("{\"type\":\"response.create\"}");
        }).onFailure(echoed::completeExceptionally);

        assertEquals("echo:{\"type\":\"response.create\"}", echoed.get(15, TimeUnit.SECONDS));
        var dialled = seen.stream().filter(s -> s.startsWith("WS ")).toList();
        assertEquals(1, dialled.size(), "dialled the stub through the override: " + seen);
        assertTrue(dialled.getFirst().contains("Bearer " + store.read(ACCOUNT).tokens().accessToken()), dialled.toString());
        assertTrue(dialled.getFirst().endsWith("| " + ACCOUNT_ID), dialled.toString());
    }

    @Test
    void aWebSocketWithAWrongProofIsRejectedBeforeAnythingIsDialled() throws Exception {
        var client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
        for (var bearer : wrongAccessProofs()) {
            var options = new WebSocketConnectOptions().setHost(ChatGptRoute.CHATGPT).setPort(mitmPort).setSsl(true)
                    .setURI("/backend-api/codex/responses").putHeader("Host", ChatGptRoute.CHATGPT)
                    .addHeader("Authorization", "Bearer " + bearer);
            var result = client.webSocket(options).toCompletionStage().toCompletableFuture();
            var failed = new CompletableFuture<Boolean>();
            result.whenComplete((ws, e) -> failed.complete(e != null));
            assertTrue(failed.get(15, TimeUnit.SECONDS), bearer);
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    static WebSocketConnectOptions webSocket(String path, String... authorizations) {
        var options = new WebSocketConnectOptions().setHost(ChatGptRoute.CHATGPT).setPort(mitmPort).setSsl(true)
                .setURI(path).putHeader("Host", ChatGptRoute.CHATGPT);
        for (var authorization : authorizations) options.addHeader("Authorization", authorization);
        return options;
    }

    /** The status a WebSocket upgrade was refused with, or 101 if it was accepted. */
    static int upgrade(WebSocketConnectOptions options) throws Exception {
        var client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false));
        var status = new CompletableFuture<Integer>();
        client.webSocket(options).onSuccess(ws -> { status.complete(101); ws.close(); })
                .onFailure(e -> status.complete(e instanceof io.vertx.core.http.UpgradeRejectedException rejected
                        ? rejected.getStatus() : -1));
        return status.get(15, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/backend-api/conversations", "/backend-api/wham/accounts/check",
            "/backend-api/codex/models", "/backend-api/codex/responses?x=1", "/backend-api/codex/../conversations",
            "/backend-api/codex/responses%2f"})
    void aWebSocketOffTheAllowlistIsRefusedWithAValidProof(String path) throws Exception {
        var status = upgrade(webSocket(path, "Bearer " + accessPlaceholder(proofs().access())));

        assertTrue(status == 400 || status == 403, path + " -> " + status);
        assertTrue(seen.isEmpty(), "nothing dialled: " + seen);
    }

    @Test
    void aWebSocketWithTwoAuthorizationsIsRefused() throws Exception {
        var bearer = "Bearer " + accessPlaceholder(proofs().access());

        assertEquals(400, upgrade(webSocket("/backend-api/codex/responses", bearer, "Bearer other")));
        assertEquals(400, upgrade(webSocket("/backend-api/codex/responses", bearer, bearer)));
        assertTrue(seen.isEmpty(), "nothing dialled: " + seen);
    }

    // --- Bodies: read only after the proof, and only so much ---

    @Test
    void aBodyIsNeverReadForACallerWithoutTheProof() throws Exception {
        // Declares far more than the bound: refused on its head, before a byte of body is waited for
        for (var bearer : List.of(accessPlaceholder(ChatGptProof.derive(OTHER_SECRET, ACCOUNT, sessionId).access()),
                "a.b." + ChatGptProof.MARKER + "x")) {
            var reply = send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, "POST /backend-api/codex/responses HTTP/1.1\r\n"
                    + "Host: chatgpt.com\r\nAuthorization: Bearer " + bearer + "\r\nContent-Length: 999999999\r\n", "");
            assertEquals(401, reply.status(), bearer);
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void aBodyPastTheBoundIsRefused() throws Exception {
        var bearer = accessPlaceholder(proofs().access());
        var declared = send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, "POST /backend-api/codex/responses HTTP/1.1\r\n"
                + "Host: chatgpt.com\r\nAuthorization: Bearer " + bearer + "\r\nContent-Length: "
                + (ChatGptRoute.MAX_BACKEND_BODY + 1) + "\r\n", "");
        assertEquals(413, declared.status());

        // A chunked body has no length to refuse up front: it is counted as it arrives
        proxy.chatGpt.maxBackendBody = 1024;
        try {
            var chunk = "x".repeat(4096);
            var chunked = send(ChatGptRoute.CHATGPT, ChatGptRoute.CHATGPT, "POST /backend-api/codex/responses HTTP/1.1\r\n"
                    + "Host: chatgpt.com\r\nAuthorization: Bearer " + bearer + "\r\nTransfer-Encoding: chunked\r\n",
                    Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n0\r\n\r\n");
            assertEquals(413, chunked.status());
        } finally {
            proxy.chatGpt.maxBackendBody = ChatGptRoute.MAX_BACKEND_BODY;
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    // --- Helpers ---

    static void assertNoRealToken(String text, ChatGptSessionStore.Tokens real) {
        assertFalse(text.contains(real.accessToken()), "real access token handed out");
        assertFalse(text.contains(real.refreshToken()), "real refresh token handed out");
        assertFalse(text.contains(real.idToken()), "real id token handed out");
        for (var part : List.of(real.accessToken(), real.idToken())) {
            assertFalse(text.contains(part.substring(part.lastIndexOf('.') + 1)), "a real signature handed out");
        }
    }

    static void awaitRefreshes(int n) throws InterruptedException {
        for (int i = 0; i < 200 && refreshes.get() < n; i++) Thread.sleep(25);
        assertEquals(n, refreshes.get());
    }

    static void awaitRevokes(int n) throws InterruptedException {
        for (int i = 0; i < 200 && revokeBodies.size() < n; i++) Thread.sleep(25);
        assertEquals(n, revokeBodies.size(), "revokes: " + revokeBodies);
    }

    interface ThrowingSupplier<T> { T get() throws Exception; }

    static <T> T uncheck(ThrowingSupplier<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
