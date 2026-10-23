package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A custom volume read or delete tells "absent" from "Incus could not say", and keeps Incus's reason. */
class StorageVolumeTest {

    @Test
    void anAbsentVolumeIsNotAnError() {
        var daemon = new FakeIncusDaemon().customVolume("cow", "dnf-cache");
        var incus = daemon.client();

        assertTrue(incus.storageVolumeExists("cow", "dnf-cache"));
        assertFalse(incus.storageVolumeExists("cow", "other"));
        assertTrue(incus.deleteStorageVolume("cow", "dnf-cache"));
        assertFalse(daemon.hasCustomVolume("cow", "dnf-cache"));
        assertFalse(incus.deleteStorageVolume("cow", "dnf-cache"));
    }

    @Test
    void aFailedReadIsNotAbsent() {
        // A 500 used to read as "no volume", so isx clean dnf reported nothing to clean
        var incus = new FakeIncusDaemon().customVolume("cow", "dnf-cache")
                .customVolumesFail(500, "database is locked").client();

        var e = assertThrows(IncusException.class, () -> incus.storageVolumeExists("cow", "dnf-cache"));

        assertTrue(e.getMessage().contains("database is locked"), e.getMessage());
    }

    @Test
    void aFailedDeleteSaysWhy() {
        // The DNF cache's "in use by a running build" warning matches on Incus's reason
        var incus = new FakeIncusDaemon().customVolume("cow", "dnf-cache")
                .customVolumesFail(400, "Storage volume is still in use").client();

        var e = assertThrows(IncusException.class, () -> incus.deleteStorageVolume("cow", "dnf-cache"));

        assertTrue(e.getMessage().contains("in use"), e.getMessage());
    }

    @Test
    void ensuringAVolumeStillTriesWhenTheLookupFails() {
        // A build's DNF cache attach must not stop at a lookup it can recover from by creating
        var daemon = new FakeIncusDaemon().customVolumesFail(503, "temporarily unavailable");

        daemon.client().ensureStorageVolume("cow", "dnf-cache");

        assertTrue(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/storage-pools/cow/volumes")),
                String.join("\n", daemon.requests()));
    }
}
