package dev.incusspawn.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

/**
 * Reads and drops the rest of a request's body the proxy has answered without reading, then closes
 * its connection: once the body ends, once none has come for {@code idle} milliseconds, or {@code cap}
 * milliseconds after the answer however it keeps coming, so a client trickling bytes cannot hold the
 * connection. These are nginx's {@code lingering_timeout} and {@code lingering_time}. Closed with
 * some of the body unread, the connection is reset, and a client still sending loses the answer with
 * it (#1617, #1623). One timer checks the last read's time, rather than one per chunk.
 * <p>
 * It takes the request's handler, end handler and exception handler.
 */
final class LingeringClose {
    private final Vertx vertx;
    private final Runnable close;
    private final long idle;
    private final long until;
    private long lastRead;
    private long timer;
    private boolean closed;

    /**
     * Runs {@code close} at once when no more of the body is to come: it has ended, or the client was
     * not yet told to send it ({@code bodyComing} false, an {@code Expect: 100-continue} never
     * answered); otherwise once the rest has been read, or the wait is over.
     */
    static void after(Vertx vertx, HttpServerRequest clientReq, boolean bodyComing, Runnable close,
                      long idle, long cap) {
        if (!bodyComing || clientReq.isEnded()) {
            close.run();
            return;
        }
        new LingeringClose(vertx, clientReq, close, idle, cap);
    }

    private LingeringClose(Vertx vertx, HttpServerRequest clientReq, Runnable close, long idle, long cap) {
        this.vertx = vertx;
        this.close = close;
        this.idle = idle;
        lastRead = nowMillis();
        until = lastRead + cap;
        timer = vertx.setTimer(Math.min(idle, cap), this::check);
        clientReq.handler(ignored -> lastRead = nowMillis());
        clientReq.endHandler(v -> close());
        // The connection closed first: nothing is left to close
        clientReq.exceptionHandler(err -> {
            closed = true;
            vertx.cancelTimer(timer);
        });
        clientReq.resume();
    }

    private void check(long id) {
        if (closed) return;
        var now = nowMillis();
        var next = Math.min(lastRead + idle, until);
        if (now >= next) close();
        else timer = vertx.setTimer(next - now, this::check);
    }

    private void close() {
        if (closed) return;
        closed = true;
        vertx.cancelTimer(timer);
        close.run();
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000;
    }
}
