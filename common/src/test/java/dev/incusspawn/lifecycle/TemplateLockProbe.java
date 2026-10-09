package dev.incusspawn.lifecycle;

import dev.incusspawn.util.HostLock;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Whether a template is held by a {@link TemplateLock#reading}, as a swap would find it. */
public final class TemplateLockProbe {

    private TemplateLockProbe() {}

    /**
     * True when {@code template} cannot be taken exclusively right now: the swap would wait. Asks
     * from another thread, since the caller's own hold would make its thread's attempt throw.
     */
    public static boolean held(String template) {
        try (var pool = Executors.newSingleThreadExecutor()) {
            return pool.submit(() -> {
                try (var lock = HostLock.acquireOrDegrade(TemplateLock.lockFile(template), "probing",
                        s -> {}, s -> {}, Duration.ofMillis(100))) {
                    return false;
                } catch (HostLock.HostLockException e) {
                    return true;
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
