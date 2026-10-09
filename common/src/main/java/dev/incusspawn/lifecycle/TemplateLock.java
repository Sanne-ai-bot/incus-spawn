package dev.incusspawn.lifecycle;

import dev.incusspawn.Environment;
import dev.incusspawn.Warnings;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.HostLock;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Replacing a template while other isx processes copy from it (#1212). A rebuild is made under a
 * temporary name and put in place by {@link #replace}: Incus cannot rename onto an existing name,
 * so the swap is a delete and a rename, and between the two the template does not exist. The swap
 * holds the template's lock exclusively, and whatever copies from a template holds it shared from
 * looking the template up until the copy is made ({@link #reading}), so no copy finds it missing
 * and none is cut off by the delete. Readers never hold each other off.
 *
 * <p>Only isx processes on this host take the lock: a raw {@code incus copy}, or isx on another
 * host using the same Incus, can still meet the gap.
 */
public final class TemplateLock {

    /**
     * Bounds the swap's wait for the copies under way, which can include starting the copy. A VM's
     * full copy can take minutes, and timing out fails a build whose work is done.
     */
    static final Duration SWAP_TIMEOUT = Duration.ofMinutes(10);

    /** The longest instance name Incus accepts. */
    public static final int MAX_INSTANCE_NAME_LENGTH = 63;

    /** What Incus accepts as an instance name; nothing else can be a template, or a file name here. */
    private static final Pattern INSTANCE_NAME =
            Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9-]{0," + (MAX_INSTANCE_NAME_LENGTH - 1) + "}");

    private TemplateLock() {}

    /** A {@link #reading} hold, released by closing it. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /** One file per template name, kept: deleting it would let two processes lock different files. */
    static Path lockFile(String template) {
        if (!INSTANCE_NAME.matcher(template).matches()) {
            throw new IllegalArgumentException("'" + template + "' is not an instance name");
        }
        return Environment.lockDir().resolve("templates").resolve(template + ".lock");
    }

    /**
     * Hold {@code template} in place until closed: a {@link #replace} waits for it. Take it before
     * looking the template up, and keep it until the copy from it is made. A name no instance can
     * have is held by nothing: the lookup will find nothing to copy.
     *
     * @param log where to say that a swap is in progress and this waits for it
     * @throws HostLock.HostLockException if a swap holds it past {@link HostLock#TIMEOUT}
     */
    public static Held reading(String template, Consumer<String> log) {
        if (template == null || !INSTANCE_NAME.matcher(template).matches()) return () -> {};
        return HostLock.acquireShared(lockFile(template), "replacing template '" + template + "'", log, Warnings::warn)::close;
    }

    /**
     * Put {@code built} in place as {@code template}, replacing the one there, once no isx process
     * is copying from it. A lock file that cannot be used at all degrades to the swap without it.
     *
     * @param log where to say that this waits for copies under way
     */
    public static void replace(IncusClient incus, String built, String template, Consumer<String> log) {
        try (var lock = HostLock.acquireOrDegrade(lockFile(template), "copying from template '" + template + "'",
                log, Warnings::warn, SWAP_TIMEOUT)) {
            incus.deleteIfExists(template);
            incus.rename(built, template);
        }
    }
}
