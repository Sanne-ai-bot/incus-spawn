package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Removing a device rewrites the instance's whole device map (PATCH cannot remove one), so a
 * device another process adds between the read and the write must not be dropped by it (#1234).
 */
class DeviceRemovalConflictTest {

    private static final String NAME = "dev";
    private static final Map<String, String> DISK = Map.of("type", "disk", "source", "/src", "path", "/dst");

    private static FakeIncusDaemon withStaleDevice() {
        return new FakeIncusDaemon().container(NAME, Map.of()).device(NAME, "stale", DISK);
    }

    @Test
    void aDeviceAddedBetweenTheReadAndTheWriteSurvivesTheRemoval() {
        var daemon = withStaleDevice();
        daemon.writeAfterRead(NAME, () -> daemon.device(NAME, "added", DISK));

        daemon.client().devicesRemoveAll(NAME, List.of("stale"));

        var devices = daemon.instance(NAME).path("devices");
        assertTrue(devices.has("added"), "the concurrent add was dropped: " + devices);
        assertFalse(devices.has("stale"), devices.toString());
        assertEquals(List.of("GET /1.0/instances/dev", "PUT /1.0/instances/dev",
                "GET /1.0/instances/dev", "PUT /1.0/instances/dev"), daemon.requests());
    }

    @Test
    void aDeviceAnotherWriterRemovedFirstIsNotWrittenAgain() {
        var daemon = withStaleDevice();
        daemon.writeAfterRead(NAME, () -> daemon.client().deviceRemove(NAME, "stale"));

        daemon.client().devicesRemoveAll(NAME, List.of("stale"));

        assertFalse(daemon.instance(NAME).path("devices").has("stale"));
        // Ours (refused), the other writer's GET + PUT, then our re-read finds nothing to do.
        assertEquals(List.of("GET /1.0/instances/dev", "GET /1.0/instances/dev", "PUT /1.0/instances/dev",
                "PUT /1.0/instances/dev", "GET /1.0/instances/dev"), daemon.requests());
    }

    @Test
    void aRemovalThatKeepsLosingTheRaceFailsRatherThanRetryingForever() {
        var daemon = withStaleDevice();
        var adds = new Runnable[IncusApi.CONFLICT_ATTEMPTS];
        for (int i = 0; i < adds.length; i++) {
            var device = "added-" + i;
            adds[i] = () -> daemon.device(NAME, device, DISK);
        }
        daemon.writeAfterRead(NAME, adds);

        assertThrows(IncusException.class, () -> daemon.client().devicesRemoveAll(NAME, List.of("stale")));

        var devices = daemon.instance(NAME).path("devices");
        assertTrue(devices.has("stale"), "a refused write changes nothing");
        for (int i = 0; i < adds.length; i++) assertTrue(devices.has("added-" + i), devices.toString());
        assertEquals(2 * IncusApi.CONFLICT_ATTEMPTS, daemon.requests().size(), String.join("\n", daemon.requests()));
    }
}
