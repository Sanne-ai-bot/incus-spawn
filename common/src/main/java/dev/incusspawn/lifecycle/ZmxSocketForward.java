package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.Platform;
import dev.incusspawn.util.Output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Shares the container's zmx socket directory with the host via an Incus
 * disk device, so host-side {@code zmx list}, {@code zmx attach}, etc.
 * work natively against container sessions.
 *
 * <p>A per-container directory under the host zmx dir is bind-mounted at
 * {@code /tmp/zmx} inside the container.  Sockets created by zmx appear
 * on the host filesystem immediately — no proxy device required.  A
 * symlink named {@code isx-<container>} in the host zmx dir points to
 * the container's {@code isx} session socket so it shows up in
 * {@code zmx list}.</p>
 */
public final class ZmxSocketForward {

    private ZmxSocketForward() {}

    private static final String DEVICE_NAME = "zmx-sockets";
    private static final String CONTAINER_ZMX_DIR = "/home/agentuser/.zmx";
    private static final String CONTAINERS_SUBDIR = "containers";
    private static final String SYMLINK_PREFIX = "isx-";

    /**
     * Resolve the host zmx socket directory using the same priority as zmx
     * itself: {@code ZMX_DIR} > {@code XDG_RUNTIME_DIR/zmx} >
     * {@code TMPDIR/zmx-<uid>} > {@code /tmp/zmx-<uid>}.
     */
    static Path hostZmxDir() {
        return hostZmxDir(System::getenv, InstanceLifecycle::getUid);
    }

    static Path hostZmxDir(UnaryOperator<String> env, Supplier<String> uid) {
        var zmxDir = env.apply("ZMX_DIR");
        if (zmxDir != null && !zmxDir.isBlank()) return Path.of(zmxDir);
        var xdgRuntime = env.apply("XDG_RUNTIME_DIR");
        if (xdgRuntime != null && !xdgRuntime.isBlank()) return Path.of(xdgRuntime, "zmx");
        var tmpdir = env.apply("TMPDIR");
        if (tmpdir != null && !tmpdir.isBlank()) return Path.of(tmpdir, "zmx-" + uid.get());
        return Path.of("/tmp", "zmx-" + uid.get());
    }

    /**
     * The host zmx dir where isx shares zmx sockets, or null on macOS, which
     * shares none.  The platform is checked first because resolving the dir
     * forks {@code id -u} on the {@code TMPDIR} and {@code /tmp} fallbacks,
     * and macOS sets {@code TMPDIR} without {@code XDG_RUNTIME_DIR}: every
     * shell would pay for a process it has no use for.
     */
    private static Path sharedHostZmxDir() {
        return sharedHostZmxDir(Platform.isMacOS(), ZmxSocketForward::hostZmxDir);
    }

    static Path sharedHostZmxDir(boolean macOS, Supplier<Path> hostZmxDir) {
        return macOS ? null : hostZmxDir.get();
    }

    public static boolean isZmxInstalled(String buildSourceJson) {
        var bs = BuildSource.fromJson(buildSourceJson);
        return bs != null && bs.getTools().containsKey("zmx");
    }

    /**
     * Configure a disk device that shares a per-container host directory at
     * the container's zmx socket path.  Also creates a symlink in the host
     * zmx dir so the default {@code isx} session is visible to host zmx.
     *
     * <p>Idempotent — safe to call on every shell-in to repair a symlink
     * that zmx may have cleaned up.</p>
     */
    public static void configure(IncusClient incus, String name, Output out) {
        var zmxDir = sharedHostZmxDir();
        if (zmxDir != null) configure(incus, name, zmxDir, out);
    }

    static void configure(IncusClient incus, String name, Path zmxDir, Output out) {
        var containerDir = zmxDir.resolve(CONTAINERS_SUBDIR).resolve(name);
        try {
            Files.createDirectories(containerDir);
        } catch (IOException e) {
            out.warn("could not create zmx directory: " + e.getMessage());
            return;
        }

        incus.devicesRemoveAll(name, java.util.List.of(DEVICE_NAME));
        var args = new java.util.ArrayList<>(java.util.List.of(
                "source=" + containerDir.toAbsolutePath(),
                "path=" + CONTAINER_ZMX_DIR,
                // The source sits on a tmpfs wiped at reboot: a start that
                // bypasses isx (plain `incus start`, boot.autostart) must not
                // fail on it.  isx's own starts recreate it first (#1044).
                "required=false"));
        HostResourceSetup.addShiftIfSupported(args, dev.incusspawn.incus.MachineType.CONTAINER);
        incus.deviceAdd(name, DEVICE_NAME, "disk", args.toArray(String[]::new));

        ensureSymlink(zmxDir, name);
    }

    /**
     * Restore the host side of the {@code zmx-sockets} device before start.
     *
     * <p>The host zmx dir normally lives under {@code XDG_RUNTIME_DIR}, a
     * tmpfs wiped on reboot or logout, and a rename leaves the device
     * pointing at the old (already deleted) directory.  Either way Incus
     * refuses to start the instance with {@code Missing source path}.</p>
     *
     * <p>Costs a single stat while the directory is there; the repair reuses
     * {@link #configure}, which also re-points a source left behind by a
     * rename.</p>
     *
     * @param instanceMetadata the instance as fetched by
     *        {@link IncusClient#instanceMetadata} — shared with the other
     *        pre-start repairs so a start pays for only one round-trip
     */
    public static void ensureHostDirForStart(IncusClient incus, String name,
                                             JqValue instanceMetadata, Output out) {
        var zmxDir = sharedHostZmxDir();
        if (zmxDir != null) ensureHostDirForStart(incus, name, zmxDir, instanceMetadata, out);
    }

    static void ensureHostDirForStart(IncusClient incus, String name, Path zmxDir,
                                      JqValue instanceMetadata, Output out) {
        var device = instanceMetadata.path("devices").path(DEVICE_NAME);
        var source = device.path("source").asText("");
        if (source.isEmpty()) return; // no device — nothing to repair

        // Healthy only if the device points at this name's directory and that
        // directory is there: a source left over from a rename can still exist
        // (it may belong to another instance by now), and the right directory
        // can exist while the device points elsewhere.  A device added before
        // it was optional (#1044) is upgraded too.  Anything else is handed
        // to configure(), which is idempotent.
        var containerDir = zmxDir.resolve(CONTAINERS_SUBDIR).resolve(name).toAbsolutePath();
        var optional = "false".equals(device.path("required").asText(""));
        if (optional && source.equals(containerDir.toString()) && Files.isDirectory(containerDir)) return;

        configure(incus, name, zmxDir, out);
    }

    /**
     * (Re-)create the symlink from the host zmx dir to the container's
     * {@code isx} session socket.  Cheap — no API calls.
     */
    public static void ensureSymlink(String name) {
        var zmxDir = sharedHostZmxDir();
        if (zmxDir != null) ensureSymlink(zmxDir, name);
    }

    private static void ensureSymlink(Path zmxDir, String name) {
        var link = zmxDir.resolve(SYMLINK_PREFIX + name);
        var target = Path.of(CONTAINERS_SUBDIR, name, "isx");
        try {
            if (Files.isSymbolicLink(link)) {
                if (target.equals(Files.readSymbolicLink(link))) return;
                Files.delete(link);
            } else if (Files.exists(link)) {
                return;
            }
            Files.createSymbolicLink(link, target);
        } catch (IOException ignored) {
        }
    }

    /**
     * Remove host-side artifacts after an instance is destroyed: the
     * per-container directory and the symlink.  The disk device itself
     * is auto-removed by Incus on instance deletion.
     */
    public static void cleanup(String name) {
        var zmxDir = sharedHostZmxDir();
        if (zmxDir == null) return;
        try {
            Files.deleteIfExists(zmxDir.resolve(SYMLINK_PREFIX + name));
        } catch (IOException ignored) {
        }
        var containerDir = zmxDir.resolve(CONTAINERS_SUBDIR).resolve(name);
        try {
            if (Files.isDirectory(containerDir)) {
                try (var stream = Files.list(containerDir)) {
                    stream.forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
                Files.deleteIfExists(containerDir);
            }
        } catch (IOException ignored) {
        }
    }
}
