package dev.incusspawn.proxy;

import dev.incusspawn.Platform;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Vert.x on the socket transport the proxy runs on: Netty's native epoll on Linux (#475),
 * kqueue on macOS with Apple Silicon (#2215).
 * A plain {@code Vertx.vertx()} runs on Java NIO, whose errors differ: a port in use is a
 * {@link java.net.BindException} there, and a {@code NativeIoException} on epoll and kqueue.
 */
final class ProxyTransport {

    private ProxyTransport() {}

    static Vertx vertx() {
        var vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(true));
        assertTrue(vertx.isNativeTransportEnabled(),
                (Platform.isMacOS() ? "kqueue" : "epoll") + " did not load: "
                        + vertx.unavailableNativeTransportCause());
        return vertx;
    }
}
