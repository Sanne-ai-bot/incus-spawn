package dev.incusspawn.proxy;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.RequestOptions;

import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The proxy's single-flight DNS cache. The lookup is held open on a latch so each test
 * controls exactly when it finishes relative to the other callers.
 */
class DnsCacheTest {

    static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /** A proxy whose lookups count themselves and wait for {@link #release}. */
    static class GatedProxy extends MitmProxy {
        final AtomicInteger lookups = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        volatile List<String> answer = List.of("192.0.2.1");
        volatile boolean fail;

        GatedProxy() {
            super(vertx, "127.0.0.1", 0, 0, "127.0.0.1",
                    new ProxyCredentials("", "", false, "", "", List.of()));
        }

        @Override
        List<String> lookupHost(String host) throws Exception {
            lookups.incrementAndGet();
            assertTrue(release.await(5, TimeUnit.SECONDS), "lookup was never released");
            if (fail) throw new UnknownHostException(host);
            return answer;
        }
    }

    private static <T> T await(Future<T> f) throws Exception {
        return f.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void concurrentCallersShareOneLookupAndItIsCached() throws Exception {
        var proxy = new GatedProxy();
        var first = proxy.upstream.resolveHost("example.test");
        var second = proxy.upstream.resolveHost("example.test");
        proxy.release.countDown();

        assertEquals("192.0.2.1", await(first));
        assertEquals("192.0.2.1", await(second));
        assertEquals("192.0.2.1", await(proxy.upstream.resolveHost("example.test")));
        assertEquals(1, proxy.lookups.get(), "One lookup must serve every caller until it expires");
    }

    @Test
    void lookupFinishingAfterAnOverrideDoesNotReplaceIt() throws Exception {
        var proxy = new GatedProxy();
        var pending = proxy.upstream.resolveHost("example.test");
        proxy.upstream.overrideDns("example.test", "198.51.100.7");
        proxy.release.countDown();

        assertEquals("192.0.2.1", await(pending), "Callers already waiting get the lookup's answer");
        assertEquals("198.51.100.7", await(proxy.upstream.resolveHost("example.test")),
                "The override was written after the lookup started, so it must win");
    }

    @Test
    void failedLookupAfterAnOverrideDoesNotDropIt() throws Exception {
        var proxy = new GatedProxy();
        proxy.fail = true;
        var pending = proxy.upstream.resolveHost("example.test");
        proxy.upstream.overrideDns("example.test", "198.51.100.7");
        proxy.release.countDown();

        assertThrows(Exception.class, () -> await(pending));
        assertEquals("198.51.100.7", await(proxy.upstream.resolveHost("example.test")));
        assertEquals(1, proxy.lookups.get());
    }

    @Test
    void failedLookupIsNotCached() throws Exception {
        var proxy = new GatedProxy();
        proxy.fail = true;
        proxy.release.countDown();
        assertThrows(Exception.class, () -> await(proxy.upstream.resolveHost("example.test")));

        proxy.fail = false;
        assertEquals("192.0.2.1", await(proxy.upstream.resolveHost("example.test")));
        assertEquals(2, proxy.lookups.get(), "A failure must be retried, not served from the cache");
    }

    @Test
    void refreshKeepsTheAddressInUseWhileDnsStillListsIt() throws Exception {
        var proxy = new GatedProxy();
        proxy.upstream.dnsCacheTtlMillis = 0;
        proxy.release.countDown();
        proxy.answer = List.of("192.0.2.1", "192.0.2.2");
        assertEquals("192.0.2.1", await(proxy.upstream.resolveHost("example.test")));

        proxy.answer = List.of("192.0.2.2", "192.0.2.1");
        assertEquals("192.0.2.1", await(proxy.upstream.resolveHost("example.test")),
                "A reordered answer must not move the host off a warm connection");

        proxy.answer = List.of("192.0.2.2", "192.0.2.3");
        assertEquals("192.0.2.2", await(proxy.upstream.resolveHost("example.test")),
                "Once DNS stops listing the address, the first one listed is taken");
        assertEquals(3, proxy.lookups.get());
    }

    @Test
    void failedConnectDropsTheCachedAddress() throws Exception {
        var proxy = new GatedProxy();
        proxy.release.countDown();
        proxy.answer = List.of("127.0.0.1", "127.0.0.2");
        assertEquals("127.0.0.1", await(proxy.upstream.resolveHost("example.test")));

        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        proxy.upstream.createClients();
        try {
            var options = new RequestOptions().setHost("example.test").setPort(closedPort);
            assertThrows(Exception.class, () -> await(proxy.upstream.requestWithAsyncDns(options)));
        } finally {
            proxy.upstream.closeClients();
        }

        proxy.answer = List.of("127.0.0.2", "127.0.0.1");
        assertEquals("127.0.0.2", await(proxy.upstream.resolveHost("example.test")),
                "An address that refused a connection must be resolved again, not kept");
        assertEquals(2, proxy.lookups.get());
    }
}
