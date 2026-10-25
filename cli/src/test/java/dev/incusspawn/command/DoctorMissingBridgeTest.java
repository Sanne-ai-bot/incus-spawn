package dev.incusspawn.command;

import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A missing incusbr0 is one finding, not an exception that ends the run (#1665). */
@ExtendWith(IsolatedHome.class)
class DoctorMissingBridgeTest {

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
    void aMissingBridgeIsOneFailAndTheOtherChecksStillRun() {
        var daemon = new FakeIncusDaemon().pool("cow", "btrfs").withoutNetwork(BridgeAddress.BRIDGE);
        var findings = new ArrayList<DoctorCommand.Finding>();

        doctorOn(daemon).runSharedChecks(findings);

        var bridge = findings.stream().filter(f -> f.label().contains(BridgeAddress.BRIDGE)).toList();
        assertEquals(1, bridge.size(), findings.toString());
        assertEquals(DoctorCommand.Status.FAIL, bridge.getFirst().status());
        assertTrue(bridge.getFirst().remediation().description().contains("isx init"),
                bridge.getFirst().remediation().description());
        // Checks that do not need the bridge still run, before and after it
        assertTrue(findings.stream().anyMatch(f -> f.label().startsWith("Storage")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.label().equals("Instance credential accounts")), findings.toString());
        // and those that only read it are skipped rather than repeating its absence
        assertTrue(findings.stream().noneMatch(f -> f.label().startsWith("Bridge DNS")
                || f.label().startsWith("Bridge subnet") || f.label().startsWith("Instance network")), findings.toString());
        if (!dev.incusspawn.Platform.isMacOS()) {
            assertTrue(findings.stream().noneMatch(f -> f.label().startsWith("Proxy")), findings.toString());
        }
    }

    @Test
    void networkExistsTellsAMissingBridgeFromAPresentOne() {
        assertTrue(new FakeIncusDaemon().client().networkExists(BridgeAddress.BRIDGE));
        assertFalse(new FakeIncusDaemon().withoutNetwork(BridgeAddress.BRIDGE).client()
                .networkExists(BridgeAddress.BRIDGE));
    }
}
