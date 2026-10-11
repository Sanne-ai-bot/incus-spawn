package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write that removes a device PUTs the instance's whole device map, so a device another
 * process adds between the flow's read and that write must survive it, as it does for
 * {@code devicesRemoveAll} (#1234, #1276).
 */
@ExtendWith(TempHome.class)
class DeviceRemovingUpdateConflictTest {

    private static final Map<String, String> DISK = Map.of("type", "disk", "source", "/src", "path", "/dst");

    @Test
    void releasingABuildAddressKeepsADeviceAddedMeanwhile() {
        var daemon = new FakeIncusDaemon().container("tpl-rebuilding", Map.of());
        var incus = daemon.client();
        InstanceNetwork.assignBuildAddress(incus, "tpl-rebuilding", incus.readInstance("tpl-rebuilding"), Map.of());
        daemon.clearRequests();
        daemon.writeAfterRead("tpl-rebuilding", () -> daemon.device("tpl-rebuilding", "added", DISK));

        InstanceNetwork.releaseBuildAddress(incus, "tpl-rebuilding");

        var instance = daemon.instance("tpl-rebuilding");
        var devices = instance.path("devices");
        assertTrue(devices.has("added"), "the concurrent add was dropped: " + devices);
        assertFalse(devices.has("eth0"), "the NIC is the profile's again: " + devices);
        assertFalse(instance.path("config").has(Metadata.STATIC_IP));
        assertEquals(List.of("PUT /1.0/instances/tpl-rebuilding", "PUT /1.0/instances/tpl-rebuilding"),
                instanceWrites(daemon, "tpl-rebuilding"), "the refused write, then the one built from the re-read");
    }

    @Test
    void droppingInheritedKvmKeepsADeviceAddedMeanwhile() {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.KVM_ENABLED, "true"))
                .device("dev-1", "kvm", Map.of("type", "unix-char", "source", "/dev/kvm", "path", "/dev/kvm"));
        daemon.writeAfterRead("dev-1", () -> daemon.device("dev-1", "added", DISK));

        InstanceLifecycle.configureBranch(daemon.client(), "dev-1", new InstanceLifecycle.BranchSettings(
                null, "8GiB", "20GiB", NetworkMode.FULL, "tpl", Map.of(), false));

        var instance = daemon.instance("dev-1");
        var devices = instance.path("devices");
        assertTrue(devices.has("added"), "the concurrent add was dropped: " + devices);
        assertFalse(devices.has("kvm"), devices.toString());
        assertFalse(instance.path("config").has(Metadata.KVM_ENABLED));
        assertEquals("true", devices.path("eth0").path("security.ipv4_filtering").asText());
        assertEquals(instance.path("config").path(Metadata.STATIC_IP).asText(),
                devices.path("eth0").path("ipv4.address").asText());
        assertEquals(List.of("PUT /1.0/instances/dev-1", "PUT /1.0/instances/dev-1"), instanceWrites(daemon, "dev-1"));
    }

    private static List<String> instanceWrites(FakeIncusDaemon daemon, String name) {
        return daemon.requests().stream()
                .filter(r -> r.equals("PUT /1.0/instances/" + name) || r.equals("PATCH /1.0/instances/" + name))
                .toList();
    }
}
