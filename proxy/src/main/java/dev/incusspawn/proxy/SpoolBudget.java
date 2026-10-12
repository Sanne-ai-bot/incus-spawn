package dev.incusspawn.proxy;

import java.util.ArrayList;
import java.util.List;

/**
 * The heap every {@link BodySpool} together may hold (#1315): bytes kept in memory for their
 * exchange, bytes received but not yet written to disk, and bytes read back but not yet written to
 * the upstream socket. Kept bytes take at most half, so the other half always serves I/O in flight
 * and a write never waits behind memory nobody will give back until their exchange ends.
 * <p>
 * A spool asks before it holds more, and waits ({@link #whenRoom}) while the budget is spent; one
 * chunk may go over, as a chunk has already been read when the spool learns it is there.
 */
final class SpoolBudget {

    static final long DEFAULT_LIMIT = 32L * 1024 * 1024;

    private final long limit;
    private long held;
    private long kept;
    private final List<Runnable> waiting = new ArrayList<>();

    SpoolBudget(long limit) {
        this.limit = limit;
    }

    long limit() {
        return limit;
    }

    /** Counts {@code n} bytes to be kept in memory until their spool closes, if half the budget allows. */
    synchronized boolean tryKeep(long n) {
        if (kept + n > limit / 2 || held + n > limit) return false;
        kept += n;
        held += n;
        return true;
    }

    /** Counts {@code n} bytes in flight to disk or to the socket. */
    synchronized void acquire(long n) {
        held += n;
    }

    synchronized boolean full() {
        return held >= limit;
    }

    synchronized long held() {
        return held;
    }

    /** Gives back bytes {@link #acquire}d, or {@link #tryKeep kept} when {@code kept}. */
    void release(long n, boolean wasKept) {
        List<Runnable> ready;
        synchronized (this) {
            held -= n;
            if (wasKept) kept -= n;
            if (held >= limit || waiting.isEmpty()) return;
            ready = new ArrayList<>(waiting);
            waiting.clear();
        }
        ready.forEach(Runnable::run);
    }

    /** Runs {@code ready} once the budget has room: at once if it has. */
    void whenRoom(Runnable ready) {
        synchronized (this) {
            if (held >= limit) {
                waiting.add(ready);
                return;
            }
        }
        ready.run();
    }
}
