package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InstanceLifecycle#readyForThisBoot} (#1720): what lets {@code isx ssh-proxy --start} use a
 * running instance without preparing it. Only a container whose current boot holds the secret isx
 * stamped for it; never a boot after a host reboot, and never a VM, which only its guest can answer for.
 */
class ReadyForThisBootTest {

    private static final String BOOT = "2026-10-28T08:00:00Z";

    private static boolean ready(String type, Map<String, String> config) {
        var daemon = new FakeIncusDaemon().instance("box", type, "Running", config).lastUsedAt("box", BOOT);
        return InstanceLifecycle.readyForThisBoot(daemon.client().instanceMetadataOrThrow("box"));
    }

    @Test
    void aContainerWhoseBootHoldsItsSecretIsReady() {
        assertTrue(ready("container", Map.of(Metadata.INSTANCE_SECRET_BOOT, InstanceLifecycle.secretBootStamp(BOOT, ""))));
    }

    @Test
    void aBootAfterAHostRebootIsNot() {
        assertFalse(ready("container", Map.of(Metadata.INSTANCE_SECRET_BOOT,
                InstanceLifecycle.secretBootStamp("2026-10-27T09:00:00Z", ""))));
        assertFalse(ready("container", Map.of()), "never stamped");
    }

    @Test
    void aVmIsNeverJudgedFromTheHost() {
        assertFalse(ready("virtual-machine", Map.of(Metadata.INSTANCE_SECRET_BOOT, InstanceLifecycle.secretBootStamp(BOOT, ""))));
    }
}
