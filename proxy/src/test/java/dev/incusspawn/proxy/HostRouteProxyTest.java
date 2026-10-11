package dev.incusspawn.proxy;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code host-routes:} (#936): a name an instance reaches on 443 that the proxy forwards to a
 * loopback controller on the host, stamping who called. The controller is a stand-in that
 * records every request it gets, so these tests see exactly what crossed the route.
 */
class HostRouteProxyTest {

    private static final String SECRET = "b".repeat(64);
    private static final String INSTANCE = "agent-1";
    private static final String TEMPLATE = "tpl-agent";
    /** Admits {@link #TEMPLATE}. */
    private static final String WORK = "work.isx.internal";
    /** Admits only another template. */
    private static final String OTHER = "other.isx.internal";
    /** Admits any instance that is not project-local. */
    private static final String OPEN = "open.isx.internal";

    @TempDir
    static Path home;
    static String origHome;
    static Vertx vertx;
    static Vertx clientVertx;
    static Context clientContext;
    static HttpClient client;
    static HttpServer controller;
    static MitmProxy proxy;
    static int port;
    static volatile String listing;
    static InstanceRegistry registry;

    record Seen(HttpMethod method, String uri, MultiMap headers, String body) {}

    static final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        var configDir = Files.createDirectories(home.resolve(".config/incus-spawn"));
        CertificateAuthority.loadOrCreate();

        vertx = Vertx.vertx();
        controller = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(body -> {
            seen.add(new Seen(req.method(), req.uri(), MultiMap.caseInsensitiveMultiMap().addAll(req.headers()),
                    body.toString()));
            req.response().putHeader("X-Controller", "yes").putHeader("Proxy-Connection", "x")
                    .putHeader("Connection", "X-Loopback").putHeader("X-Loopback", "for the loopback hop").end("hello " + req.getHeader("X-Isx-Instance"));
        })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var target = "127.0.0.1:" + controller.actualPort();

        Files.writeString(configDir.resolve("config.yaml"), """
                host-routes:
                  %s:
                    target: %s
                    templates: [%s]
                  %s:
                    target: %s
                    templates: [tpl-elsewhere]
                  %s:
                    target: %s
                """.formatted(WORK, target, TEMPLATE, OTHER, target, OPEN, target));
        proxy = new MitmProxy(vertx, "127.0.0.1", 0, 0, "127.0.0.1", ConfigFingerprint.load());
        registry = new InstanceRegistry(new IncusClient() {
            @Override
            public io.hyperfoil.tools.jjq.value.JqValue listJsonConfig() {
                return io.hyperfoil.tools.jjq.value.JqValues.parse(listing);
            }
        });
        proxy.useInstanceRegistry(registry);
        ContainerTls.startInBackground(proxy);
        port = proxy.mitmPort();

        clientVertx = Vertx.vertx();
        clientContext = clientVertx.getOrCreateContext();
        client = clientVertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true)
                .setVerifyHost(false).setForceSni(true));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (clientVertx != null) clientVertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @BeforeEach
    void anAgentInstance() {
        instance(TEMPLATE, "");
        seen.clear();
    }

    /** The instance at 127.0.0.1, built from {@code template}, with this start's secret recorded. */
    private static void instance(String template, String extraConfig) {
        listing = """
                [{"name":"%s","status":"Running","config":{
                   "%s":"127.0.0.1", "%s":"%s", "%s":"%s"%s}}]
                """.formatted(INSTANCE, Metadata.STATIC_IP, Metadata.INSTANCE_SECRET_SHA256,
                InstanceSecret.sha256(SECRET), Metadata.PROFILE, template, extraConfig);
        registry.refresh();
    }

    record Reply(int status, MultiMap headers, String body) {}

    /** A request whose SNI is {@code sni} and whose Host header is {@code host}. */
    private static Reply send(String sni, String host, String path, MultiMap headers, String body) throws Exception {
        var options = new RequestOptions().setMethod(body == null ? HttpMethod.GET : HttpMethod.POST)
                .setServer(SocketAddress.inetSocketAddress(port, "127.0.0.1"))
                .setHost(sni).setPort(443).setURI(path);
        headers.forEach(options::addHeader);
        if (!sni.equals(host)) options.putHeader("Host", host);
        var result = Promise.<Reply>promise();
        clientContext.runOnContext(v -> client.request(options)
                .compose(req -> body == null ? req.send() : req.send(Buffer.buffer(body)))
                .compose(resp -> resp.body().map(b -> new Reply(resp.statusCode(), resp.headers(), b.toString())))
                .onComplete(result));
        return result.future().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    private static Reply send(String name, MultiMap headers) throws Exception {
        return send(name, name, "/report?x=1", headers, null);
    }

    private static MultiMap withSecret() {
        return MultiMap.caseInsensitiveMultiMap().add(InstanceSecret.HEADER, SECRET);
    }

    @Test
    void theRightSecretReachesTheControllerWithTheProxysIdentityOnly() throws Exception {
        var headers = withSecret()
                .add("X-Isx-Instance", "someone-else")
                .add("x-isx-template", "tpl-forged")
                .add("X-Isx-Anything", "forged")
                // A CGI-style controller reads these as HTTP_X_ISX_INSTANCE, the proxy's own header
                .add("X_Isx_Instance", "someone-else")
                .add("x_isx-template", "tpl-forged")
                .add("X.Isx.Instance", "someone-else")
                .add("Connection", "keep-alive, X-Hop")
                .add("X-Hop", "for this hop only")
                .add("X-Other", "kept");
        var reply = send(WORK, WORK, "/report?x=1", headers, "{\"done\":true}");

        assertEquals(200, reply.status(), reply.body());
        assertEquals("hello " + INSTANCE, reply.body());
        assertEquals("yes", reply.headers().get("X-Controller"));
        assertNull(reply.headers().get("Proxy-Connection"), "hop-by-hop on the way back too");
        assertNull(reply.headers().get("X-Loopback"), "a header the controller named in Connection: stays on its hop");
        assertEquals(1, seen.size());
        var got = seen.getFirst();
        assertEquals(HttpMethod.POST, got.method());
        assertEquals("/report?x=1", got.uri());
        assertEquals("{\"done\":true}", got.body());
        assertEquals(List.of(INSTANCE), got.headers().getAll("X-Isx-Instance"), "the proxy's identity replaces the client's");
        assertEquals(List.of(TEMPLATE), got.headers().getAll("X-Isx-Template"));
        assertNull(got.headers().get("X-Isx-Anything"), "no client-sent X-Isx-* header crosses");
        // As a CGI-style controller reads a name: every non-alphanumeric character as one
        got.headers().names().forEach(name -> assertFalse(name.toUpperCase().replaceAll("[^A-Z0-9]", "_").startsWith("X_ISX_")
                && !List.of("X-Isx-Instance", "X-Isx-Template").contains(name), "a variant of an identity header crossed: " + name));
        assertNull(got.headers().get("X-Hop"), "a header the client named in Connection: is hop-by-hop");
        assertNull(got.headers().get(InstanceSecret.HEADER), "the controller never sees the secret");
        assertEquals("kept", got.headers().get("X-Other"));
    }

    @Test
    void anAbsoluteFormTargetReachesTheControllerAsItsPathOnly() throws Exception {
        var reply = send(WORK, WORK, "http://elsewhere.internal/report?x=1", withSecret(), null);
        assertEquals(200, reply.status(), reply.body());
        assertEquals("/report?x=1", seen.getFirst().uri());
    }

    @Test
    void aMissingOrWrongSecretIsRefusedBeforeTheController() throws Exception {
        assertEquals(403, send(WORK, MultiMap.caseInsensitiveMultiMap()).status());
        assertEquals(403, send(WORK, MultiMap.caseInsensitiveMultiMap().add(InstanceSecret.HEADER, "c".repeat(64))).status());
        assertEquals(List.of(), seen);
    }

    @Test
    void anSniAndHostThatDisagreeAreRefused() throws Exception {
        // A route's name on the connection cannot carry a request for another domain, nor the other way round
        assertEquals(421, send(WORK, OPEN, "/", withSecret(), null).status());
        assertEquals(421, send(WORK, ProxyConfig.MCP_DOMAIN, "/", withSecret(), null).status());
        assertEquals(421, send("api.anthropic.com", WORK, "/", withSecret(), null).status());
        assertEquals(List.of(), seen);
    }

    @Test
    void aNameUnderARouteIsNotFound() throws Exception {
        assertEquals(404, send("x." + WORK, withSecret()).status());
        assertEquals(List.of(), seen);
    }

    @Test
    void aNameNotConfiguredIsNotRouted() {
        // Not intercepted at all: the proxy has no certificate for it
        assertThrows(Exception.class, () -> send("nope.isx.internal", withSecret()));
        assertEquals(List.of(), seen);
    }

    @Test
    void aWebSocketIsRefused() throws Exception {
        // WebSocket clients dial the name, so this one resolves it as an instance's bridge DNS would
        var wsVertx = Vertx.vertx(new io.vertx.core.VertxOptions().setAddressResolverOptions(
                new io.vertx.core.dns.AddressResolverOptions().setHostsValue(Buffer.buffer("127.0.0.1 " + WORK + "\n"))));
        try {
            var connect = new io.vertx.core.http.WebSocketConnectOptions()
                    .setHost(WORK).setPort(port).setSsl(true).setURI("/ws")
                    .addHeader(InstanceSecret.HEADER, SECRET);
            var failure = assertThrows(Exception.class, () -> wsVertx.createWebSocketClient(
                            new io.vertx.core.http.WebSocketClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false))
                    .connect(connect).toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS));
            assertTrue(String.valueOf(failure.getCause()).contains("404"), String.valueOf(failure.getCause()));
            assertEquals(List.of(), seen);
        } finally {
            wsVertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void theRouteNamesAreInterceptedForCertificatesAndBridgeDns() {
        assertTrue(proxy.allInterceptedDomains().containsAll(List.of(WORK, OTHER, OPEN)),
                proxy.allInterceptedDomains().toString());
    }

    @Test
    void aTemplateTheRouteDoesNotListIsRefused() throws Exception {
        assertEquals(403, send(OTHER, withSecret()).status());
        assertEquals(200, send(OPEN, withSecret()).status(), "a route listing no templates admits any");
        assertEquals(1, seen.size());
    }

    @Test
    void aProjectLocalInstanceIsNeverAdmitted() throws Exception {
        instance(TEMPLATE, ", \"" + Metadata.PROJECT + "\":\"some-repo\"");
        assertEquals(403, send(WORK, withSecret()).status());
        assertEquals(403, send(OPEN, withSecret()).status());
        assertEquals(List.of(), seen);
    }
}
