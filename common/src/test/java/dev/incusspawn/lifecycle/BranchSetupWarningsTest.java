package dev.incusspawn.lifecycle;

import dev.incusspawn.Environment;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * The warnings of a branch's post-start setup go where the rest of the branch's do (#1646): the
 * TUI branches on the released terminal and keeps what {@link BuildOutput#warn} said in its
 * warning log, since the shell that follows the branch draws over the terminal.
 */
@ExtendWith(TempHome.class)
class BranchSetupWarningsTest {

    private static List<String> branchWarnings(IncusClient incus) {
        var request = new BranchFlow.Request("tpl-dev", "dev-1", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), true, Map.of());
        var warnings = new ArrayList<String>();
        try (var ignored = BuildOutput.copyWarningsTo(warnings::add)) {
            BranchFlow.create(incus, BranchFlow.preflight(incus, request, Map.of()));
        }
        return warnings;
    }

    private static IncusClient settingUp(FakeIncusDaemon daemon, boolean ready) {
        var incus = spy(daemon.client());
        doReturn(ready).when(incus).pollUntilReady(eq("dev-1"), anyInt(), anyMap(), any(String[].class));
        return incus;
    }

    @Test
    void aSetupThatDidNotCompleteIsWarnedOfInTheLog() {
        var daemon = new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));

        var warnings = branchWarnings(settingUp(daemon, false));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("Container setup may not be complete")),
                "warned " + warnings);
    }

    @Test
    void anSshHostEntryThatFailedIsWarnedOfInTheLog() throws IOException {
        Files.createDirectories(Environment.sshDir());
        Files.writeString(Environment.sshKeyFile(), "private\n");
        Files.writeString(Environment.sshPubKeyFile(), "ssh-ed25519 AAAA isx\n");
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.PROFILE, "tpl-dev", Metadata.SSH_SETUP, "true"));
        var incus = settingUp(daemon, true);
        doThrow(new IllegalStateException("no address yet")).when(incus).getContainerIpv4("dev-1");

        var warnings = branchWarnings(incus);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("Failed to configure SSH host entry: no address yet")),
                "warned " + warnings);
    }
}
