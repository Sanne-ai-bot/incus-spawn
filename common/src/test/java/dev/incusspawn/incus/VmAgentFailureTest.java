package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class VmAgentFailureTest {

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
    void matchesTheUncolouredStatusLinesOfATermDumbConsole() {
        // TERM=dumb on the kernel command line (#1255) drops systemd's colour. From the console of
        // the base VM image's own kernel, its incus-agent-setup made to fail
        var log = """
                [  OK  ] Started dbus-broker.service - D-Bus System Message Bus.\r
                         Starting incus-agent.service - Incus - agent...\r
                [  OK  ] Started serial-getty@ttyS0.service - Serial Getty on ttyS0.\r
                [FAILED] Failed to start incus-agent.service - Incus - agent.\r
                See 'systemctl status incus-agent.service' for details.\r
                [  OK  ] Reached target multi-user.target - Multi-User System.\r
                """;

        var failure = List.of("[FAILED] Failed to start incus-agent.service - Incus - agent.");
        assertEquals(failure, VmAgentFailure.matchingLines(log));
        assertEquals(failure, VmAgentFailure.unrecoveredLines(log));
        assertEquals(List.of(), VmAgentFailure.unrecoveredLines(
                log + "[  OK  ] Started incus-agent.service - Incus - agent.\r\n"));
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
