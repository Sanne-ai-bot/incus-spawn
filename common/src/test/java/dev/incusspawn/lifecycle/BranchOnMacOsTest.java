package dev.incusspawn.lifecycle;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1254: macOS runs containers only, so a VM source or KVM passthrough is refused before
 * anything is created, with a message that says so, instead of failing in a lower layer.
 */
@ExtendWith(TempHome.class)
class BranchOnMacOsTest {

    private java.util.function.BooleanSupplier platform;

    @BeforeEach
    void onMacOs() {
        platform = BranchFlow.macOS;
        BranchFlow.macOS = () -> true;
    }

    @AfterEach
    void restorePlatform() {
        BranchFlow.macOS = platform;
    }

    private static Map<String, ImageDef> template(String type) {
        var def = new ImageDef();
        def.setName("tpl-dev");
        def.setType(type);
        return Map.of("tpl-dev", def);
    }

    private static BranchFlow.Request request(Boolean kvm) {
        return new BranchFlow.Request("tpl-dev", "dev-1", false, kvm, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
    }

    @Test
    void kvmIsRefusedBeforeAnyIncusRequest() {
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of());
        var e = assertThrows(BranchFlow.BranchException.class,
                () -> BranchFlow.preflight(daemon.client(), request(true), template("container")));
        assertTrue(e.getMessage().contains("KVM passthrough is not available on macOS"), e.getMessage());
        assertTrue(e.getMessage().contains("--kvm"), e.getMessage());
        assertEquals(List.of(), daemon.requests());
    }

    @Test
    void aVmSourceIsRefusedBeforeAnythingIsCreated() {
        var daemon = new FakeIncusDaemon().instance("tpl-dev", "virtual-machine", "Stopped", Map.of());
        var e = assertThrows(BranchFlow.BranchException.class,
                () -> BranchFlow.preflight(daemon.client(), request(null), template("vm")));
        assertTrue(e.getMessage().contains("VM instances are not available on macOS"), e.getMessage());
        assertTrue(e.getMessage().contains("'tpl-dev' is a VM"), e.getMessage());
        assertFalse(daemon.client().exists("dev-1"));
    }

    @Test
    void aKvmTemplateBranchesWithoutKvmAndSaysWhy() {
        // The template's instance-mode is how it was built, not this branch's request: off, with a
        // note, as GUI is outside a Wayland session. isx mcp has no --no-kvm to ask for that itself.
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of(Metadata.INSTANCE_MODE, "kvm"));
        var defaults = BranchFlow.defaultsFor("tpl-dev", daemon.client().instanceMetadata("tpl-dev"), template("kvm"));
        assertFalse(defaults.kvm());
        assertNotNull(defaults.kvmNote());

        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request(null), template("kvm")));
        assertFalse(daemon.instance("dev-1").path("devices").has("kvm"));
    }

    @Test
    void onlyAVmOrKvmIsRefused() {
        assertNull(BranchFlow.vmUnsupportedOnMacOs("tpl-dev", MachineType.CONTAINER));
        assertNull(BranchFlow.kvmUnsupportedOnMacOs(false));
        assertNotNull(BranchFlow.vmUnsupportedOnMacOs("tpl-dev", MachineType.VM));
        assertNotNull(BranchFlow.kvmUnsupportedOnMacOs(true));
        BranchFlow.macOS = () -> false;
        assertNull(BranchFlow.vmUnsupportedOnMacOs("tpl-dev", MachineType.VM));
        assertNull(BranchFlow.kvmUnsupportedOnMacOs(true));
    }
}
