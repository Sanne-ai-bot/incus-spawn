package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigWatcherTest {

    @Test
    void anEditToAToolUnderASearchPathReloads(@TempDir Path dir) throws Exception {
        // #890: only the config directory was watched, so a proxy tool under a search path
        // could change without a reload.
        var configDir = Files.createDirectories(dir.resolve("config"));
        var toolsDir = Files.createDirectories(dir.resolve("dotfiles/tools"));
        var reloads = new java.util.concurrent.Semaphore(0);
        var watcher = new ConfigWatcher(configDir, () -> List.of(toolsDir), reloads::release);
        watcher.start();
        try {
            assertTrue(reloadsOnEdit(toolsDir, reloads), "an edited tool under a search path should reload the proxy");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void aToolDirectoryRecreatedIsWatchedAgain(@TempDir Path dir) throws Exception {
        // A re-clone of the search path drops the directory's watch; the new one must be watched.
        var configDir = Files.createDirectories(dir.resolve("config"));
        var toolsDir = Files.createDirectories(dir.resolve("dotfiles/tools"));
        var reloads = new java.util.concurrent.Semaphore(0);
        var watcher = new ConfigWatcher(configDir, () -> List.of(toolsDir), reloads::release);
        watcher.start();
        try {
            assertTrue(reloadsOnEdit(toolsDir, reloads));
            try (var files = Files.list(toolsDir)) {
                for (var f : files.toList()) Files.delete(f);
            }
            Files.delete(toolsDir);
            // On Linux the files' deletion is an edit, and its reload must pass before the next
            // check so it cannot stand in for it. macOS may instead cancel the directory's key
            // without reporting them, so a reload is waited for, not required.
            reloads.tryAcquire(2, TimeUnit.SECONDS);
            Thread.sleep(1000);
            reloads.drainPermits();
            Files.createDirectories(toolsDir);
            assertTrue(reloadsOnEdit(toolsDir, reloads), "a recreated tool directory should be watched again");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void aHostRouteSecretDeletedOrChangedReloads(@TempDir Path dir) throws Exception {
        // #936: deleting or rewriting a secret takes effect at once, on every platform
        var configDir = Files.createDirectories(dir.resolve("config"));
        var secrets = Files.createDirectories(configDir.resolve(HostRouteSecret.DIR));
        var secret = Files.writeString(secrets.resolve("work.isx.internal" + HostRouteSecret.SUFFIX), "a".repeat(64));
        var reloads = new java.util.concurrent.Semaphore(0);
        var watcher = new ConfigWatcher(configDir, List::of, reloads::release);
        watcher.start();
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            var reloaded = false;
            while (!reloaded && System.nanoTime() < deadline) {
                Files.deleteIfExists(secret);
                Files.writeString(secret, "b".repeat(64));
                reloaded = reloads.tryAcquire(500, TimeUnit.MILLISECONDS);
            }
            // Not asserted that other files there are ignored: macOS's polling watcher reports
            // earlier writes late, and an extra reload costs nothing
            assertTrue(reloaded, "a changed route secret should reload the proxy");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void aChmodOfTheHostRoutesDirectoryReloadsOnLinux(@TempDir Path dir) throws Exception {
        // What a 503's warning asks for (chmod 700 it) must serve the route again without a restart.
        // Linux only: macOS's polling watcher compares modification times, never modes
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"));
        var configDir = Files.createDirectories(dir.resolve("config"));
        var secrets = Files.createDirectories(configDir.resolve(HostRouteSecret.DIR));
        var reloads = new java.util.concurrent.Semaphore(0);
        var watcher = new ConfigWatcher(configDir, List::of, reloads::release);
        watcher.start();
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            var reloaded = false;
            for (int i = 0; !reloaded && System.nanoTime() < deadline; i++) {
                java.nio.file.Files.setPosixFilePermissions(secrets,
                        java.nio.file.attribute.PosixFilePermissions.fromString(i % 2 == 0 ? "rwxrwxrwx" : "rwx------"));
                reloaded = reloads.tryAcquire(500, TimeUnit.MILLISECONDS);
            }
            assertTrue(reloaded, "a chmod of host-routes/ should reload the proxy");
        } finally {
            watcher.stop();
        }
    }

    /**
     * Edits a tool until a reload is seen: the watcher registers directories on its own thread,
     * and an edit made before that is not an event. 30s covers a polling WatchService.
     */
    private static boolean reloadsOnEdit(Path toolsDir, java.util.concurrent.Semaphore reloads) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (int i = 0; System.nanoTime() < deadline; i++) {
            Files.writeString(toolsDir.resolve("foo.yaml"), "name: foo\ndescription: v" + i + "\n");
            if (reloads.tryAcquire(500, TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }
}
