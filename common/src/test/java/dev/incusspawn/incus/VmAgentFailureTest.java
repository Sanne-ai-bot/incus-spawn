package dev.incusspawn.incus;

import dev.incusspawn.Environment;
import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(TempHome.class)
class VmAgentFailureTest {

    @Test
    void aSavedConsoleLogIsTheUsersAloneAndReplacesAnOpenOne() throws Exception {
        // An older, world-readable file is replaced by a new one, not written through and narrowed
        var file = Environment.consoleLogFile("vm1");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "old");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        var saved = VmAgentFailure.save("vm1", "boot log\n");

        assertEquals(file, saved);
        assertEquals("boot log\n", Files.readString(file));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

        // And again, over its own earlier copy: a second failure of the same VM is still saved
        assertEquals(file, VmAgentFailure.save("vm1", "second\n"));
        assertEquals("second\n", Files.readString(file));
    }

    @Test
    void aNewConsoleLogDirectoryIsCreatedPrivate() throws Exception {
        var saved = VmAgentFailure.save("vm2", "x");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(saved.getParent())));
    }

    @Test
    void matchesTheSelinuxDenialAndTheFailedUnitFrom842() {
        var log = """
                [    3.912345] systemd[1]: Starting incus-agent.service - Incus - agent...
                [    4.105712] audit: type=1400 audit(1758000000.123:4): avc:  denied  { listen } for  pid=1288 comm="incus-agent" scontext=system_u:system_r:init_t:s0 tcontext=system_u:system_r:init_t:s0 tclass=vsock_socket permissive=0
                [    4.110000] systemd[1]: Failed to start incus-agent.service - Incus - agent.
                [  OK  ] Reached target multi-user.target
                """;

        var lines = VmAgentFailure.matchingLines(log);

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("tclass=vsock_socket"));
        assertTrue(lines.get(1).endsWith("Failed to start incus-agent.service - Incus - agent."));
    }

    @Test
    void stripsConsoleColourAndCarriageReturns() {
        var log = "\u001B[0;1;31mFAILED\u001B[0m] Failed to start \u001B[0;1;39mincus-agent.service\u001B[0m - Incus - agent.\r\n";

        assertEquals(List.of("FAILED] Failed to start incus-agent.service - Incus - agent."),
                VmAgentFailure.matchingLines(log));
    }

    @Test
    void ignoresOtherUnitsAndOtherDenials() {
        var log = """
                Failed to start sshd.service - OpenSSH server daemon.
                avc:  denied  { read } for  pid=900 comm="chronyd" tclass=file permissive=0
                incus-agent: Connected to host
                """;

        assertEquals(List.of(), VmAgentFailure.matchingLines(log));
    }

    @Test
    void reportsEachLineOnceAndBoundsTheCount() {
        var log = "Failed to start incus-agent.service - Incus - agent.\n".repeat(5)
                + IntStream.range(0, 20)
                        .mapToObj(i -> "avc: denied { listen } comm=\"incus-agent\" pid=" + i + "\n")
                        .collect(Collectors.joining());

        var lines = VmAgentFailure.matchingLines(log);

        assertEquals(VmAgentFailure.MAX_LINES, lines.size());
        assertEquals(1, lines.stream().filter(l -> l.startsWith("Failed to start")).count());
    }

    @Test
    void emptyOrMissingLogMatchesNothing() {
        assertEquals(List.of(), VmAgentFailure.matchingLines(""));
        assertEquals(List.of(), VmAgentFailure.matchingLines(null));
    }

    @Test
    void aFailureSystemdRecoveredFromIsNotCurrent() {
        var failed = "[FAILED] Failed to start incus-agent.service - Incus - agent.";
        var started = "[  OK  ] Started incus-agent.service - Incus - agent.";

        assertEquals(List.of(), VmAgentFailure.unrecoveredLines(failed + "\n" + started + "\n"));
        assertEquals(List.of(failed), VmAgentFailure.unrecoveredLines(failed + "\n"));
        assertEquals(List.of(failed), VmAgentFailure.unrecoveredLines(started + "\n" + failed + "\n"),
                "a failure after a start is current again");
        assertEquals(List.of(), VmAgentFailure.unrecoveredLines(null));
    }
}
