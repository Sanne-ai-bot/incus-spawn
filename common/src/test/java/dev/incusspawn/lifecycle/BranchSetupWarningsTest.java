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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        return branchWarnings(incus, null);
    }

    private static List<String> branchWarnings(IncusClient incus, Path inbox) {
        var request = new BranchFlow.Request("tpl-dev", "dev-1", false, false, NetworkMode.AIRGAP,
                inbox, null, null, null, List.of(), true, Map.of());
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

    @Test
    void anInboxThatIsNoDirectoryIsWarnedOfInTheLog() throws IOException {
        var notADirectory = Files.writeString(Path.of(System.getProperty("user.home"), "inbox"), "");
        var daemon = new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));

        var warnings = branchWarnings(settingUp(daemon, true), notADirectory);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("is not a directory, skipping")),
                "warned " + warnings);
    }

    /** Configuring the branch, before its start: a host that refuses the filtering is told of in the log too. */
    @Test
    void ipFilteringTheHostRefusedIsWarnedOfInTheLog() {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of())
                .refuseWritesContaining("security.ipv4_filtering");
        var warnings = new ArrayList<String>();

        try (var ignored = BuildOutput.copyWarningsTo(warnings::add)) {
            InstanceLifecycle.configureBranch(daemon.client(), "dev-1", new InstanceLifecycle.BranchSettings(
                    null, "8GiB", "20GiB", NetworkMode.FULL, "tpl-dev", Map.of(), false));
        }

        // One warning, naming the instance and what it means
        assertEquals(1, warnings.size(), "warned " + warnings);
        assertTrue(warnings.get(0).startsWith("could not enable IP spoofing protection on dev-1: "), warnings.get(0));
        assertTrue(warnings.get(0).endsWith("; instances on this host can impersonate each other's credential accounts."),
                warnings.get(0));
    }
}
