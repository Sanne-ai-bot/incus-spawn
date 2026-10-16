package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two branches of one template must not share its {@code /etc/machine-id} (#1305): journal IDs,
 * DHCP DUIDs and {@code systemd-id128} derivations would take them for one host.
 */
@ExtendWith(TempHome.class)
class BranchMachineIdTest {

    private static final String MACHINE_ID = "/etc/machine-id";

    private static void branch(FakeIncusDaemon daemon, String name) {
        // Airgapped and not started: a branch started later boots with the same file
        var request = new BranchFlow.Request("tpl-dev", name, false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));
    }

    @Test
    void eachContainerBranchBootsWithAMachineIdOfItsOwn() {
        var daemon = new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
        branch(daemon, "dev-1");
        branch(daemon, "dev-2");

        var first = daemon.pushedContent("dev-1", MACHINE_ID);
        var second = daemon.pushedContent("dev-2", MACHINE_ID);
        // An ID, not "uninitialized": that would make the boot a first boot, whose preset-all
        // undoes every unit the image and the template build enabled, systemd-networkd included
        assertTrue(first != null && first.matches("[0-9a-f]{32}\n"), "pushed " + first);
        assertTrue(second != null && second.matches("[0-9a-f]{32}\n"), "pushed " + second);
        assertNotEquals(first, second);
        assertEquals("0444", daemon.pushedMode("dev-1", MACHINE_ID));
    }

    @Test
    @ExtendWith(LinuxBranching.class)
    void aVmBranchIsPushedNothingItCouldNotTakeWhileStopped() {
        var daemon = new FakeIncusDaemon().instance("tpl-dev", "virtual-machine", "Stopped",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
        branch(daemon, "dev-1");

        assertEquals("virtual-machine", daemon.instance("dev-1").path("type").asText());
        assertNull(daemon.pushedContent("dev-1", MACHINE_ID));
    }
}
