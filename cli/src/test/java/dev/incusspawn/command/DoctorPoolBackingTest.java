package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** How doctor reads the CoW pool's backing from the listing, end to end through its checks (#1656). */
@ExtendWith(IsolatedHome.class)
class DoctorPoolBackingTest {

    private static DoctorCommand doctorOn(FakeIncusDaemon daemon) {
        var incus = daemon.client();
        return new DoctorCommand() {
            @Override
            IncusClient incus() {
                return incus;
            }
        };
    }

    @Test
    void aLoopPoolIsOfferedItsMountOptionsAndTheyReachThePool() throws Exception {
        var daemon = new FakeIncusDaemon().pool("cow", "btrfs")
                .poolConfig("cow", Map.of("source", "/var/lib/incus/disks/cow.img", "size", "100GiB"));
        var findings = new ArrayList<DoctorCommand.Finding>();

        doctorOn(daemon).runSharedChecks(findings);

        var mount = findings.stream().filter(f -> f.label().contains("not mounted for scratch space")).toList();
        assertEquals(1, mount.size(), findings.toString());
        mount.getFirst().remediation().action().run();
        // Compression depends on this host's /var/lib/incus/disks; the rest does not
        assertTrue(daemon.poolConfigOf("cow").get("btrfs.mount_options").startsWith("user_subvol_rm_allowed,noatime,commit=300"),
                daemon.poolConfigOf("cow").toString());
    }

    @Test
    void aSubvolumePoolGetsNoBackingFindings() {
        var daemon = new FakeIncusDaemon().pool("cow", "btrfs")
                .poolConfig("cow", Map.of("source", "/var/lib/incus/storage-pools/cow"));
        var findings = new ArrayList<DoctorCommand.Finding>();

        doctorOn(daemon).runSharedChecks(findings);

        assertTrue(findings.stream().noneMatch(f -> f.label().startsWith("CoW pool")), findings.toString());
    }

    @Test
    void aListingIncusCannotGiveIsNotTakenForNoPool() {
        var daemon = new FakeIncusDaemon().pool("cow", "btrfs").failPoolListing();
        var findings = new ArrayList<DoctorCommand.Finding>();

        doctorOn(daemon).runSharedChecks(findings);

        assertTrue(findings.stream().noneMatch(f -> f.label().startsWith("No copy-on-write")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.label().equals("Storage pool")
                && f.detail().contains("could not check")), findings.toString());
    }
}
