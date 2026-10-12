package dev.incusspawn.proxy;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;

import java.util.ArrayList;
import java.util.List;

/**
 * A request body the proxy must hold until it has been sent for good (#1315): a Vertex model call,
 * whose URL is built from its body and which is sent again after a 401, so it is kept until an answer
 * other than a 401 has come and the body has all gone, or the exchange ends. Its start is kept in memory while the
 * {@link SpoolBudget} allows, and the rest goes to a {@link SpoolStore} file, never more than the
 * budget in heap at once whatever the body's size or the number of bodies held.
 * <p>
 * Backpressure both ways: {@link #writeQueueFull()} asks the client to wait while this spool's disk
 * writes queue up or the budget is spent, and {@link #replay} reads the file back no faster than
 * the upstream socket takes it. Used from the exchange's own event loop; {@link #close()} ends it on
 * every path, however often and from whatever thread it is called, giving back what it kept once.
 */
final class BodySpool {

    /** How much may wait for the disk before the client is paused. */
    static final int FILE_QUEUE_BYTES = 256 * 1024;
    /** How much is read back from the disk at once. */
    static final int READ_BYTES = 64 * 1024;

    /** The host is too low on disk to hold the body: answered with a 507. */
    static final class SpoolFull extends Exception {
        SpoolFull(String message) {
            super(message);
        }
    }

    private final SpoolBudget budget;
    private final SpoolStore store;
    private final Context context;

    private final List<Buffer> memory = new ArrayList<>();
    private long kept;
    private long size;
    private Future<SpoolStore.SpoolFile> file;
    private long filePending;
    private boolean ended;
    /** Set once, by whichever close comes first, on whatever thread: only that one gives back what was kept. */
    private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean closed;
    private Throwable failure;
    private Runnable drain;
    private boolean waitingForBudget;
    private Handler<Throwable> failureHandler;
    private Promise<Void> written;
    private volatile Promise<Void> replaying;

    BodySpool(SpoolBudget budget, SpoolStore store, Context context) {
        this.budget = budget;
        this.store = store;
        this.context = context;
    }

    /** The body's length so far: all of it, once {@link #end()} has completed. */
    long size() {
        return size;
    }

    /** Whether any of the body went to disk. */
    boolean spilled() {
        return file != null;
    }

    void write(Buffer chunk) {
        if (closed || failure != null) return;
        var n = chunk.length();
        if (n == 0) return;
        if (file == null && budget.tryKeep(n)) {
            memory.add(chunk);
            kept += n;
            size += n;
            return;
        }
        if (!store.admit(n)) {
            fail(new SpoolFull(store.refusal()));
            return;
        }
        budget.acquire(n);
        filePending += n;
        if (file == null) file = store.open();
        var position = size - kept;
        size += n;
        file.compose(f -> f.write(chunk, position)).onComplete(ar -> {
            filePending -= n;
            budget.release(n, false);
            if (ar.succeeded()) {
                store.written(n);
            } else {
                store.abandoned(n);
                fail(ar.cause());
            }
            settle();
        });
    }

    /** Whether the writer should wait: this spool's disk writes queue up, or the budget is spent. */
    boolean writeQueueFull() {
        return filePending > FILE_QUEUE_BYTES || budget.full();
    }

    /** Runs {@code ready}, once, when {@link #writeQueueFull()} is no longer so. */
    void drainHandler(Runnable ready) {
        drain = ready;
        settle();
    }

    /** Told once, when the body cannot be held: {@link SpoolFull}, or the disk's own error. */
    void failureHandler(Handler<Throwable> handler) {
        failureHandler = handler;
    }

    /** The body has all been written: completes once every disk write has, or fails as the spool did. */
    Future<Void> end() {
        ended = true;
        written = Promise.promise();
        if (failure != null) written.fail(failure);
        settle();
        return written.future();
    }

    /**
     * Writes the whole body to {@code out}, from the start: the part kept in memory, then the rest read
     * back from disk, each piece only while {@code out}'s queue has room. Completes once all of it was
     * handed to {@code out}, and fails if a read or a write does, or the spool is closed or replayed
     * again meanwhile. Each send of the exchange replays it.
     */
    Future<Void> replay(WriteStream<Buffer> out) {
        stopReplay();
        var replay = new Replay(out);
        replaying = replay.done;
        // Called from wherever the upstream request was set up, which may be another request's event
        // loop (a shared token fetch completes there): the spool is only ever read on its own
        context.runOnContext(v -> replay.start());
        return replay.done.future();
    }

    /**
     * Closes the spool once the body has all been sent: for an answer that is not a 401, which no send
     * follows, so a long streamed answer does not keep the body for as long as it lasts.
     */
    void closeOnceSent() {
        context.runOnContext(v -> {
            var current = replaying;
            if (current == null) close();
            // The replay may end on the upstream socket's event loop: the close comes back here
            else current.future().onComplete(sent -> context.runOnContext(x -> close()));
        });
    }

    /** Stops the replay under way, if any: before another starts (a 401's retry), or when the spool closes. */
    void stopReplay() {
        stopReplay("replaced by another send");
    }

    private void stopReplay(String why) {
        var current = replaying;
        if (current != null) current.tryFail(why);
    }

    /**
     * One replay: a single cursor over the body, moved only by {@link #pump()} on the spool's event
     * loop. Its one drain handler is installed before anything is written, so a drain that lands just
     * as the queue is found full is never missed; a drain nobody waited for, or several, only pump
     * again from where the cursor is, so no byte is sent twice.
     */
    private final class Replay {
        final WriteStream<Buffer> out;
        final Promise<Void> done = Promise.promise();
        int memoryIndex;
        long position;
        boolean reading;
        boolean waitingForRoom;
        SpoolStore.SpoolFile f;

        Replay(WriteStream<Buffer> out) {
            this.out = out;
        }

        void start() {
            // The socket's drains run on its own event loop: they come back to the spool's
            out.drainHandler(v -> context.runOnContext(x -> pump()));
            if (file == null) {
                pump();
                return;
            }
            file.onComplete(opened -> {
                if (opened.failed()) {
                    done.tryFail(opened.cause());
                    return;
                }
                f = opened.result();
                pump();
            });
        }

        void pump() {
            while (!done.future().isComplete() && !reading && !waitingForRoom) {
                if (closed) {
                    done.tryFail("the exchange ended");
                    return;
                }
                if (out.writeQueueFull()) return;
                if (memoryIndex < memory.size()) {
                    out.write(memory.get(memoryIndex++));
                    continue;
                }
                var fileSize = size - kept;
                if (position >= fileSize) {
                    done.tryComplete();
                    return;
                }
                if (budget.full()) {
                    waitingForRoom = true;
                    budget.whenRoom(() -> context.runOnContext(v -> {
                        waitingForRoom = false;
                        pump();
                    }));
                    return;
                }
                var length = (int) Math.min(READ_BYTES, fileSize - position);
                var at = position;
                position += length;
                reading = true;
                budget.acquire(length);
                f.read(at, length).onComplete(read -> {
                    reading = false;
                    if (read.failed()) {
                        budget.release(length, false);
                        done.tryFail(read.cause());
                        return;
                    }
                    if (done.future().isComplete()) {
                        budget.release(length, false);
                        return;
                    }
                    out.write(read.result()).onComplete(w -> {
                        budget.release(length, false);
                        if (w.failed()) done.tryFail(w.cause());
                    });
                    pump();
                });
            }
        }
    }

    /**
     * Ends the spool: gives back what it kept and closes its file, whose storage the system frees
     * with the last handle. Disk writes still pending give back their own share as they complete.
     */
    void close() {
        if (!closing.compareAndSet(false, true)) return;
        if (Vertx.currentContext() != context) {
            // State only the spool's event loop touches: it is cleared there
            context.runOnContext(v -> closeHere());
            return;
        }
        closeHere();
    }

    private void closeHere() {
        closed = true;
        stopReplay("the exchange ended");
        drain = null;
        memory.clear();
        budget.release(kept, true);
        kept = 0;
        if (file != null) {
            file.onSuccess(f -> f.close().onFailure(e -> ProxyLog.warn("Could not close a spool file: " + e.getMessage())));
        }
    }

    private void fail(Throwable cause) {
        if (failure != null) return;
        failure = cause;
        drain = null;
        if (written != null) written.tryFail(cause);
        if (failureHandler != null) failureHandler.handle(cause);
    }

    private void settle() {
        if (ended && filePending == 0 && written != null && failure == null) written.tryComplete();
        if (drain == null || closed || filePending > FILE_QUEUE_BYTES) return;
        if (budget.full()) {
            if (!waitingForBudget) {
                waitingForBudget = true;
                budget.whenRoom(() -> context.runOnContext(v -> {
                    waitingForBudget = false;
                    settle();
                }));
            }
            return;
        }
        var ready = drain;
        drain = null;
        ready.run();
    }
}
