package dev.incusspawn.proxy;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * At most one piece of work in flight per key (#1567): a caller that arrives while one is
 * running joins it and gets its result, instead of asking upstream again. Two identical
 * lookups in flight could only disagree, and each disagreement needed an ordering rule.
 * <p>
 * The key is released when the work completes, before anyone hears of it, so a caller that
 * comes after starts its own: its lookup then follows the first one's writes. Each caller
 * continues on its own Vert.x context, as it would have with work of its own. The work
 * must not depend on the caller that started it, since others share its result.
 */
final class SingleFlight<K, V> {

    private final ConcurrentHashMap<K, Future<V>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong joined = new AtomicLong();

    /** The result of the work in flight for {@code key}, or of {@code work}, started now if none is. */
    Future<V> join(K key, Supplier<Future<V>> work) {
        var promise = Promise.<V>promise();
        var running = inFlight.putIfAbsent(key, promise.future());
        if (running != null) {
            joined.incrementAndGet();
            return onCallersContext(running);
        }
        Future<V> started;
        try {
            started = work.get();
            if (started == null) started = Future.failedFuture(new NullPointerException("no work for " + key));
        } catch (Throwable e) {
            // An Error too: a key left on a promise that never completes would hang every later caller
            started = Future.failedFuture(e);
        }
        started.onComplete(ar -> {
            inFlight.remove(key, promise.future());
            promise.handle(ar);
        });
        return onCallersContext(promise.future());
    }

    private static <V> Future<V> onCallersContext(Future<V> result) {
        var context = Vertx.currentContext();
        if (context == null) return result;
        var resumed = Promise.<V>promise();
        // Inline when it completes on the caller's own context, as the leader's mostly does
        result.onComplete(ar -> {
            if (Vertx.currentContext() == context) resumed.handle(ar);
            else context.runOnContext(v -> resumed.handle(ar));
        });
        return resumed.future();
    }

    int size() {
        return inFlight.size();
    }

    boolean isEmpty() {
        return inFlight.isEmpty();
    }

    /** How many callers joined work already in flight. */
    long joined() {
        return joined.get();
    }
}
