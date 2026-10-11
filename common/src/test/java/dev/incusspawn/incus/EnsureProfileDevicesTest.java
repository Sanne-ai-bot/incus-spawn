package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Repairing a profile adds only what it lacks: a root disk and a NIC under any names count, and
 * the user's own are never replaced.
 */
class EnsureProfileDevicesTest {

    @Test
    void aProfileWithARootDiskAndANicUnderOtherNamesIsLeftAlone() {
        var mine = Map.of(
                "rootfs", Map.of("type", "disk", "path", "/", "pool", "fast"),
                "net0", Map.of("type", "nic", "network", "mybr", "name", "net0"));
        var daemon = new FakeIncusDaemon().profile("scratch", mine);

        assertEquals(List.of(), daemon.client().ensureProfileDevices("scratch", "default", "incusbr0"));
        assertEquals(mine, daemon.profileDevices("scratch"));
    }

    @Test
    void theDefaultProfileAsInitLeavesItNeedsNothing() {
        var daemon = new FakeIncusDaemon();
        var before = daemon.profileDevices("default");

        assertEquals(List.of(), daemon.client().ensureDefaultProfileDevices("default", "incusbr0"));
        assertEquals(before, daemon.profileDevices("default"));
    }

    @Test
    void anEmptyProfileGetsARootDiskAndANic() {
        var daemon = new FakeIncusDaemon().profile("scratch", Map.of());

        assertEquals(List.of("root", "eth0"), daemon.client().ensureProfileDevices("scratch", "default", "incusbr0"));
        assertEquals(Map.of(
                "root", Map.of("type", "disk", "path", "/", "pool", "default"),
                "eth0", Map.of("type", "nic", "name", "eth0", "network", "incusbr0")),
                daemon.profileDevices("scratch"));
    }

    @Test
    void aDiskThatIsNotTheRootDoesNotCountAsOne() {
        var daemon = new FakeIncusDaemon().profile("scratch", Map.of(
                "data", Map.of("type", "disk", "path", "/data", "source", "/srv"),
                "net0", Map.of("type", "nic", "network", "incusbr0", "name", "net0")));

        assertEquals(List.of("root"), daemon.client().ensureProfileDevices("scratch", "default", "incusbr0"));
    }
}
