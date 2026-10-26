package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

public final class KvmPassthrough {

    private static final List<String> DEVICES = List.of("kvm", "vhost-vsock");

    private KvmPassthrough() {}

    /**
     * Pass the host's KVM through to {@code name}.
     *
     * @return why it could not, for the caller to warn of as one warning with what it does
     *         instead (#1690); null when it could
     */
    public static Unavailable configureKvm(IncusClient incus, String name, Predicate<String> exists) {
        var problem = kvmProblem(exists);
        if (problem != null) return problem;

        BuildOutput.step("Enabling KVM passthrough.");
        incus.devicesRemoveAll(name, DEVICES);
        incus.deviceAdd(name, "kvm", "unix-char",
                "source=/dev/kvm",
                "path=/dev/kvm");
        if (exists.test("/dev/vhost-vsock")) {
            incus.deviceAdd(name, "vhost-vsock", "unix-char",
                    "source=/dev/vhost-vsock",
                    "path=/dev/vhost-vsock");
        }
        incus.configSet(name, Metadata.KVM_ENABLED, "true");
        return null;
    }

    /** Why KVM cannot be passed through on a host where {@code exists} says which paths exist, or null when it can. */
    static Unavailable kvmProblem(Predicate<String> exists) {
        if (exists.test("/dev/kvm")) return null;
        var onAVm = exists.test("/sys/hypervisor") || exists.test("/proc/xen");
        // Two lines each, short enough for a terminal, the command on its own
        return onAVm
                ? new Unavailable("/dev/kvm not found on the host.",
                        "This host appears to be a VM: enable nested virtualization on the hypervisor,",
                        "then verify /dev/kvm is present before using --kvm.")
                : new Unavailable("/dev/kvm not found on the host.",
                        "Ensure your CPU supports hardware virtualization (VT-x/AMD-V) and load the kvm module:",
                        "sudo modprobe kvm_intel  (or kvm_amd)");
    }

    /**
     * Add to {@code update} the removal of KVM passthrough an instance inherited, given its
     * current state. Contributes nothing when there is none, so an instance without KVM devices
     * costs no device rewrite.
     */
    public static void removeKvm(JqValue instance, InstanceUpdate update) {
        for (var device : DEVICES) {
            if (instance.path("devices").has(device)) update.removeDevice(device);
        }
        if (instance.path("config").has(Metadata.KVM_ENABLED)) update.unset(Metadata.KVM_ENABLED);
    }
}
