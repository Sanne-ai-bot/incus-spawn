package dev.incusspawn.incus;

/** Names for disk devices hot-plugged into a running instance. */
public final class DeviceNames {

    /**
     * The longest disk device name a running VM can mount when hot-plugged. Incus shares a VM disk as
     * the virtiofs tag {@code incus_<name>}, and a tag is at most 36 bytes, so it hashes a longer one;
     * on a hot-plug, though, incus-agent is still asked to mount the unhashed tag and never finds it
     * (#1513, Incus 6.23).
     */
    public static final int VM_HOTPLUG_MAX_LENGTH = 30;

    private DeviceNames() {}

    /** {@code name} if it fits, else its start and a hash of the whole name, which keeps names distinct. */
    public static String hotplug(String name) {
        return name.length() <= VM_HOTPLUG_MAX_LENGTH ? name
                : hotplug(name, "-" + Integer.toHexString(name.hashCode() & 0x7fffffff));
    }

    /** {@code base} cut short enough that {@code base + suffix} fits, so a distinguishing suffix survives. */
    public static String hotplug(String base, String suffix) {
        var room = VM_HOTPLUG_MAX_LENGTH - suffix.length();
        return (base.length() > room ? base.substring(0, room) : base) + suffix;
    }
}
