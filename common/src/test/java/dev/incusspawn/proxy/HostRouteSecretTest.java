package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The secret a host route's controller checks (#936): owner-only, kept once made, and refused
 * rather than used when it is not safe, without ever saying what it is.
 */
class HostRouteSecretTest {

    @TempDir
    Path config;

    private final List<String> warnings = new ArrayList<>();

    private String secret(String route) {
        return HostRouteSecret.loadOrCreate(config, route, warnings::add);
    }

    @Test
    void aNewSecretIsOwnerOnlyAndKept() throws Exception {
        var first = secret("work.isx.internal");
        assertNotNull(first);
        assertEquals(64, first.length());
        var file = HostRouteSecret.file(config, "work.isx.internal");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.getParent()));
        assertEquals(first, secret("work.isx.internal"), "the controller and the proxy read the same one");
        assertNotEquals(first, secret("other.isx.internal"));
        assertEquals(List.of(), warnings);
        try (var leftovers = Files.list(file.getParent())) {
            assertEquals(2, leftovers.count(), "no temporary file is left behind");
        }
    }

    @Test
    void oneWrittenByHandIsUsedAsWritten() throws Exception {
        var file = HostRouteSecret.file(config, "work.isx.internal");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "  " + "A".repeat(40) + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertEquals("A".repeat(40), secret("work.isx.internal"));
    }

    @Test
    void aReadOnlyOwnerOnlySecretIsUsed() throws Exception {
        var file = HostRouteSecret.file(config, "work.isx.internal");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "B".repeat(40));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        assertEquals("B".repeat(40), secret("work.isx.internal"));
    }

    @Test
    void aDirectoryOthersCanWriteToIsRefused() throws Exception {
        // Anyone who can write it could swap a secret for a link or their own
        var dir = Files.createDirectories(config.resolve("host-routes"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertNull(secret("work.isx.internal"));
        assertFalse(Files.exists(HostRouteSecret.file(config, "work.isx.internal")), "nothing is created there");
        assertTrue(warnings.getFirst().contains("chmod go-w"), warnings.getFirst());
    }

    @Test
    void aLeftoverTemporaryIsRemovedWhenASecretIsMade() throws Exception {
        // A proxy killed after linking leaves a second name for a live secret, which rotation would miss
        var dir = Files.createDirectories(config.resolve("host-routes"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        var stale = Files.writeString(dir.resolve(".secret-123.tmp"), "o".repeat(64));
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 7_200_000L));
        var fresh = Files.writeString(dir.resolve(".secret-456.tmp"), "f".repeat(64));
        assertNotNull(secret("work.isx.internal"));
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh), "one another proxy may still be writing is left alone");
    }

    @Test
    void aDirectorySomeoneElseOwnsIsRefused() throws Exception {
        // A host-routes/ left by a sudo isx is root's: its mode alone would pass it
        var other = HostRouteSecret.ownUidForTest(config) + 1;
        assertNull(HostRouteSecret.loadOrCreate(config, "work.isx.internal", warnings::add, other));
        assertTrue(warnings.getFirst().contains("belongs to uid") && warnings.getFirst().contains("chown"), warnings.toString());
        assertFalse(Files.exists(HostRouteSecret.file(config, "work.isx.internal")), "nothing is created there");
    }

    @Test
    void aSecretFileSomeoneElseOwnsIsRefused() throws Exception {
        // The file's own check, not the directory's: read() is given the file alone
        assertNotNull(secret("work.isx.internal"));
        var other = HostRouteSecret.ownUidForTest(config) + 1;
        var refused = assertThrows(HostRouteSecret.Unusable.class,
                () -> HostRouteSecret.read(HostRouteSecret.file(config, "work.isx.internal"), other));
        assertTrue(refused.getMessage().startsWith("belongs to uid"), refused.getMessage());
    }

    @Test
    void aDirectoryOnlyItsOwnersGroupCanWriteToIsAccepted() throws Exception {
        // A umask of 002, Fedora's default, makes a user's directories writable by that user's own group
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwxrwxr-x"));
        var dir = Files.createDirectories(config.resolve("host-routes"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwx---"));
        try {
            assertNotNull(secret("work.isx.internal"), warnings.toString());
        } finally {
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aConfigDirectoryOthersCanWriteToIsRefused() throws Exception {
        // Else host-routes/ itself could be renamed away and replaced between the checks and the open
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwxrwxrwx"));
        try {
            assertNull(secret("work.isx.internal"));
            assertTrue(warnings.getFirst().contains("others can write to"), warnings.toString());
        } finally {
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void refusalsSayTheRouteAnswers503() throws Exception {
        var dir = Files.createDirectories(config.resolve("host-routes"));
        Files.writeString(dir.resolve("short.isx.internal.secret"), "s".repeat(16));
        Files.setPosixFilePermissions(dir.resolve("short.isx.internal.secret"), PosixFilePermissions.fromString("rw-------"));
        assertNull(secret("short.isx.internal"));
        assertTrue(warnings.getFirst().startsWith("Refusing every request to host route 'short.isx.internal' (503)"),
                warnings.getFirst());
    }

    @Test
    void aFifoIsRefusedWithoutBeingOpened() throws Exception {
        // Opening one blocks until a writer comes, which would hang startup or reload() for good
        var dir = Files.createDirectories(config.resolve("host-routes"));
        var fifo = dir.resolve("fifo.isx.internal.secret");
        Process mkfifo;
        try {
            mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no mkfifo here: " + e.getMessage());
            return;
        }
        assertEquals(0, mkfifo.waitFor());
        var secret = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> secret("fifo.isx.internal"));
        assertNull(secret);
        assertTrue(warnings.getFirst().contains("is not a regular file"), warnings.toString());
    }

    @Test
    void anUnsafeOrUnusableSecretIsRefusedWithoutSayingIt() throws Exception {
        var dir = Files.createDirectories(config.resolve("host-routes"));
        var readable = dir.resolve("readable.isx.internal.secret");
        Files.writeString(readable, "r".repeat(64));
        Files.setPosixFilePermissions(readable, PosixFilePermissions.fromString("rw-r-----"));
        var shortOne = dir.resolve("short.isx.internal.secret");
        Files.writeString(shortOne, "s".repeat(16));
        Files.setPosixFilePermissions(shortOne, PosixFilePermissions.fromString("rw-------"));
        var target = Files.writeString(config.resolve("elsewhere"), "t".repeat(64));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        Files.createSymbolicLink(dir.resolve("linked.isx.internal.secret"), target);

        assertNull(secret("readable.isx.internal"));
        assertNull(secret("short.isx.internal"));
        assertNull(secret("linked.isx.internal"), "a link could point the proxy at any file");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("linked.isx.internal") && w.contains("is a link")),
                "refused as a link, not only by the mode a link happens to have: " + warnings);
        assertEquals(3, warnings.size(), warnings.toString());
        for (var warning : warnings) {
            for (var value : List.of("r".repeat(64), "s".repeat(16), "t".repeat(64))) {
                assertFalse(warning.contains(value), "a warning shows a secret: " + warning);
            }
        }
    }
}
