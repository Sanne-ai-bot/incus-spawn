package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.RecordingOutput.Event;
import dev.incusspawn.util.RecordingOutput.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A branch that cannot pass the GPU or KVM through says why in one warning, the cause first and
 * its advice and what the branch does instead under it, which the TUI keeps in its log (#1690).
 * They used to print the cause straight to stderr, so the log kept only "Continuing without".
 */
class PassthroughWarningsTest {

    @Test
    void aHostWithoutKvmSaysWhyAndWhatToDoInOneWarning() {
        var whyNot = KvmPassthrough.kvmProblem(path -> false);
        var out = new RecordingOutput();

        whyNot.warn(out, "Continuing without KVM.");

        assertEquals(List.of(new Event(Kind.WARN, "/dev/kvm not found on the host.",
                "Ensure your CPU supports hardware virtualization (VT-x/AMD-V) and load the kvm module:",
                "sudo modprobe kvm_intel  (or kvm_amd)",
                "Continuing without KVM.")), out.events());
    }

    @Test
    void aVmHostIsToldToEnableNestedVirtualization() {
        var whyNot = KvmPassthrough.kvmProblem(Set.of("/sys/hypervisor")::contains);

        assertEquals(new Unavailable("/dev/kvm not found on the host.",
                "This host appears to be a VM: enable nested virtualization on the hypervisor,",
                "then verify /dev/kvm is present before using --kvm."), whyNot);
    }

    @Test
    void aHostWithKvmHasNoProblem() {
        assertNull(KvmPassthrough.kvmProblem(Set.of("/dev/kvm")::contains));
    }

    @Test
    void outsideAWaylandSessionTheBranchSaysWhyAndWhatToDoInOneWarning() {
        var out = new RecordingOutput();
        GuiPassthrough.waylandProblem(Map.of(), path -> false).warn(out, "Continuing without GUI passthrough.");

        assertEquals(List.of(new Event(Kind.WARN, "GUI passthrough requires WAYLAND_DISPLAY and XDG_RUNTIME_DIR.",
                "Make sure you are running isx from a Wayland graphical session.",
                "Continuing without GUI passthrough.")), out.events());
    }
}
