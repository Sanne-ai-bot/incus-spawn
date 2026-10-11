package dev.incusspawn.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What the names the proxy answers itself ({@code isx mcp}, host routes) share: identifying the
 * instance behind a request by address and per-start secret, and refusing a request in plain text.
 */
final class CallerCheck {

    private CallerCheck() {}

    /**
     * Who sent {@code req}, by {@code identify} against {@code registry}'s snapshot, handed to
     * {@code then}; null when nobody passes. A refusal right after a start may come from a
     * snapshot that predates it, since a start rotates the secret without telling the proxy:
     * then one refresh, throttled by {@link InstanceRegistry#wantsMissRefresh}, and one more try.
     * Whatever {@code then} throws on that path answers 502, as it would from the request handler.
     */
    static <T> void identify(Vertx vertx, HttpServerRequest req, InstanceRegistry registry,
                             Function<InstanceRegistry, T> identify, Consumer<T> then) {
        var caller = identify.apply(registry);
        if (caller != null || !registry.wantsMissRefresh()) {
            then.accept(caller);
            return;
        }
        vertx.executeBlocking(() -> {
            registry.refresh();
            return identify.apply(registry);
        }, false).onComplete(ar -> {
            try {
                then.accept(ar.succeeded() ? ar.result() : null);
            } catch (RuntimeException e) {
                ProxyLog.warn("Request to " + req.authority() + " failed: " + e.getMessage());
                refuse(req, 502, "Internal proxy error");
            }
        });
    }

    /** Answer {@code req} with {@code status} and a plain-text line, reading and dropping any body. */
    static void refuse(HttpServerRequest req, int status, String message) {
        req.resume();
        var resp = req.response();
        if (resp.ended() || resp.closed()) return;
        try {
            resp.setStatusCode(status).putHeader("Content-Type", "text/plain; charset=utf-8").end(message + "\n");
        } catch (IllegalStateException ignored) {
            // the client went away meanwhile
        }
    }
}
