package dev.incusspawn.proxy;

import java.io.IOException;
import java.util.function.LongSupplier;

/**
 * Keeps the spool from filling the host's disk (#1315): it leaves free min(15% of the filesystem,
 * 4 GiB), journald's {@code SystemKeepFree} default, the rule the same host's logger already keeps.
 * <p>
 * Free space is sampled, never asked per write, and a sample is out of date from the moment it is
 * taken: a write is admitted only if the sample, less every write still pending and every write
 * completed since the sample began, leaves the reserve free. A new sample is taken off the event
 * loop every {@link #RESAMPLE_BYTES} written or {@link #RESAMPLE_MILLIS}, whichever comes first.
 * Space a closed spool gave back counts only from the next sample on.
 */
final class DiskReserve {

    static final long MAX_KEEP_FREE = 4L * 1024 * 1024 * 1024;
    static final long RESAMPLE_BYTES = 8L * 1024 * 1024;
    static final long RESAMPLE_MILLIS = 1000;

    /** The filesystem the spool writes to. */
    interface Probe {
        long usable() throws IOException;

        long total() throws IOException;
    }

    /** Runs a blocking task off the event loop. */
    interface Blocking {
        void run(Runnable task);
    }

    private final Probe probe;
    private final Blocking blocking;
    private final LongSupplier clock;
    private final long keepFree;

    private long sampled;
    private long sampledAt;
    private boolean sampling;
    private long pending;
    private long written;
    /** {@link #written} when the current sample began: what was written since is not in it. */
    private long writtenAtSample;
    private long writtenAtLastResample;

    DiskReserve(Probe probe, Blocking blocking, LongSupplier clock) throws IOException {
        this.probe = probe;
        this.blocking = blocking;
        this.clock = clock;
        this.keepFree = keepFree(probe.total());
        this.sampled = probe.usable();
        this.sampledAt = clock.getAsLong();
    }

    static long keepFree(long total) {
        return Math.min(total * 15 / 100, MAX_KEEP_FREE);
    }

    /** Whether anything can be written at all: the check made before a body is accepted. */
    boolean hasRoom() {
        boolean room;
        synchronized (this) {
            room = free() > keepFree;
        }
        // Also when there is none: a sample may show space given back since
        maybeResample();
        return room;
    }

    /** What the sample says is free, less what was written since it and what is pending. */
    synchronized long free() {
        return sampled - pending - (written - writtenAtSample);
    }

    /** Reserves {@code n} bytes about to be written, or false when that would eat into the reserve. */
    boolean admit(long n) {
        boolean admitted;
        synchronized (this) {
            admitted = free() - n >= keepFree;
            if (admitted) pending += n;
        }
        maybeResample();
        return admitted;
    }

    private void maybeResample() {
        long startedWith;
        synchronized (this) {
            if (sampling || (written - writtenAtLastResample < RESAMPLE_BYTES
                    && clock.getAsLong() - sampledAt < RESAMPLE_MILLIS)) {
                return;
            }
            sampling = true;
            writtenAtLastResample = written;
            startedWith = written;
        }
        try {
            blocking.run(() -> sample(startedWith));
        } catch (RuntimeException e) {
            ProxyLog.warn("Could not sample the spool's free disk space: " + e.getMessage());
            synchronized (this) {
                sampling = false;
            }
        }
    }

    private void sample(long startedWith) {
        long usable = -1;
        try {
            usable = probe.usable();
        } catch (IOException | RuntimeException e) {
            ProxyLog.warn("Could not read the spool's free disk space: " + e.getMessage());
        }
        synchronized (this) {
            if (usable >= 0) {
                sampled = usable;
                writtenAtSample = startedWith;
            }
            sampledAt = clock.getAsLong();
            sampling = false;
        }
    }

    /** A reserved write reached the disk. */
    synchronized void written(long n) {
        pending -= n;
        written += n;
    }

    /** A reserved write never happened, or failed. */
    synchronized void abandoned(long n) {
        pending -= n;
    }

    /** For the refusal: what the host has, and keeps. */
    synchronized String describe() {
        return "the host has " + mib(Math.max(0, free())) + " free for it and keeps " + mib(keepFree);
    }

    private static String mib(long bytes) {
        return bytes / (1024 * 1024) + " MiB";
    }
}
