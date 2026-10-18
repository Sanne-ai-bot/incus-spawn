package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static helpers for Wayland GUI passthrough on Incus containers.
 * <p>
 * These were previously private statics on {@code BranchCommand}; they are
 * shared by {@code BranchCommand}, {@code Tui}, and {@code ShellCommand}
 * so they belong in the lifecycle layer.
 */
public final class GuiPassthrough {

    private GuiPassthrough() {}

    // Env vars needed for Wayland GUI passthrough (toolkit backends + quirk suppressors).
    private static final Map<String, String> WAYLAND_ENV = Map.of(
            "GDK_BACKEND", "wayland",
            "QT_QPA_PLATFORM", "wayland",
            "SDL_VIDEODRIVER", "wayland",
            "MOZ_ENABLE_WAYLAND", "1",
            "ELECTRON_OZONE_PLATFORM_HINT", "wayland",
            "NO_AT_BRIDGE", "1");

    private static final Pattern WAYLAND_DISPLAY_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]+");

    /** Where host sockets are mounted in the guest; its own XDG_RUNTIME_DIR stays guest-local. */
    private static final String MOUNT_ROOT = "/mnt/host-xdg";

    /** The Wayland display socket's device name. */
    private static final String WAYLAND_DEVICE = "xdg-wayland";

    /**
     * The audio sockets GUI mounts when the host has them, device name to path in
     * {@code XDG_RUNTIME_DIR}. Only these and the Wayland display are passed through, never the
     * whole {@code XDG_RUNTIME_DIR}: that directory also holds the session D-Bus, podman, ssh-agent
     * and keyring sockets, all owned by the host user whose uid the guest shares, so a mount of it
     * would hand the guest the host. PipeWire's {@code pipewire-0-manager} is left out on purpose:
     * a client on it gets session-manager rights (screencast and camera nodes, rerouting), which
     * playback does not need.
     */
    private static final Map<String, String> AUDIO_SOCKETS = Map.of(
            "xdg-pipewire", "pipewire-0",
            "xdg-pulse", "pulse/native");

    /**
     * Every device {@link #configureGui} may set, so {@link #removeGui} and {@link #hasGui} clear
     * and detect them whatever a source carried over. Beyond what is mounted now, it holds two
     * names an earlier isx added and this one does not: {@code xdg-runtime}, the whole
     * {@code XDG_RUNTIME_DIR} mount, and {@code xdg-pipewire-manager}. A non-GUI branch of such a
     * source must still drop them.
     */
    private static final List<String> GUI_DEVICES = guiDeviceNames();

    private static List<String> guiDeviceNames() {
        var names = new ArrayList<String>();
        names.add("gpu");
        names.add("xdg-runtime");
        names.add("xdg-pipewire-manager");
        names.add(WAYLAND_DEVICE);
        names.addAll(AUDIO_SOCKETS.keySet());
        return List.copyOf(names);
    }

    /** Whether isx runs in a Wayland session it could pass through. */
    public static boolean inWaylandSession() {
        return waylandProblem() == null;
    }

    /** Why GUI passthrough cannot work from this session, one line per element, or null when it can. */
    private static String waylandProblem() {
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (xdgRuntimeDir == null || waylandDisplay == null) {
            return "Error: GUI passthrough requires WAYLAND_DISPLAY and XDG_RUNTIME_DIR.\n"
                    + "Make sure you are running isx from a Wayland graphical session.";
        }
        if (!WAYLAND_DISPLAY_PATTERN.matcher(waylandDisplay).matches()) {
            return "Error: WAYLAND_DISPLAY contains invalid characters: " + waylandDisplay;
        }
        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(hostSocket))) {
            return "Error: Wayland socket not found at " + hostSocket + "\n"
                    + "Make sure you are running isx from a Wayland graphical session.";
        }
        return null;
    }

    /**
     * Configure GUI passthrough on a (stopped) container: GPU device, Wayland
     * socket mount, environment variables, and tmpfiles.d for XDG_RUNTIME_DIR.
     */
    public static boolean configureGui(IncusClient incus, String name) {
        var problem = waylandProblem();
        if (problem != null) {
            System.err.println(problem);
            return false;
        }
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");

        BuildOutput.step("Enabling GUI passthrough.");
        var uid = InstanceLifecycle.getUid();
        var waylandSocketPath = MOUNT_ROOT + "/" + waylandDisplay;
        incus.updateFresh(name, guiUpdate(xdgRuntimeDir, waylandDisplay, uid));

        if (!pushWaylandFiles(incus, name, waylandSocketPath, uid)) {
            System.err.println("Warning: GUI devices configured but profile.d scripts failed to install.");
            System.err.println("GUI may not work in login shells.");
            return false;
        }
        return true;
    }

    /**
     * The single instance write that enables GUI passthrough: the GPU device, a bind mount per
     * socket the host has, the environment keys, and the removal of any GUI device a source carried
     * over (incus copy keeps them) that this set does not include — the earlier whole-directory
     * {@code xdg-runtime} mount among them. Package-private so a test can assert what is mounted.
     *
     * <p>Each socket is bind-mounted under {@code /mnt/host-xdg} (not {@code /run/user/<uid>}, where
     * systemd-logind's own tmpfs would hide it) at the path it has in {@code XDG_RUNTIME_DIR}, so
     * whatever reached it under the old whole-directory mount still does. {@code required=false}: a
     * socket gone by start (the compositor or PipeWire restarted since the branch) is skipped rather
     * than failing the start. A file bind-mount pins the socket's inode, so one recreated after
     * start is not re-resolved as the directory mount's was — the instance must be restarted.
     */
    static InstanceUpdate guiUpdate(String xdgRuntimeDir, String waylandDisplay, String uid) {
        var update = new InstanceUpdate();
        var mounted = new HashSet<String>();
        update.replaceDevice("gpu", Map.of("type", "gpu"));
        mounted.add("gpu");
        for (var mount : socketMounts(xdgRuntimeDir, waylandDisplay)) {
            update.replaceDevice(mount.device(), Map.of(
                    "type", "disk",
                    "source", xdgRuntimeDir + "/" + mount.relPath(),
                    "path", MOUNT_ROOT + "/" + mount.relPath(),
                    "required", "false"));
            mounted.add(mount.device());
        }
        // Env via container config (seen by init and direct exec); pushWaylandFiles repeats it in
        // profile.d for login shells, which su - resets.
        update.config("environment.WAYLAND_DISPLAY", MOUNT_ROOT + "/" + waylandDisplay);
        update.config("environment.XDG_RUNTIME_DIR", "/run/user/" + uid);
        WAYLAND_ENV.forEach((k, v) -> update.config("environment." + k, v));
        GUI_DEVICES.stream().filter(device -> !mounted.contains(device)).forEach(update::removeDevice);
        return update;
    }

    /** A host socket to pass through, by its device name and its path under {@code XDG_RUNTIME_DIR}. */
    private record SocketMount(String device, String relPath) {}

    /**
     * The sockets to mount for {@code name}: the Wayland display (already checked to exist by
     * {@link #waylandProblem}), plus each audio socket the host has. A missing audio socket is
     * skipped rather than mounted as an empty path.
     */
    private static List<SocketMount> socketMounts(String xdgRuntimeDir, String waylandDisplay) {
        var mounts = new ArrayList<SocketMount>();
        mounts.add(new SocketMount(WAYLAND_DEVICE, waylandDisplay));
        AUDIO_SOCKETS.forEach((device, relPath) -> {
            if (Files.exists(Path.of(xdgRuntimeDir, relPath))) mounts.add(new SocketMount(device, relPath));
        });
        return mounts;
    }

    /**
     * Remove GUI passthrough from a container that inherited it via incus copy.
     * Clears devices, environment keys, metadata, and profile.d/tmpfiles.d scripts.
     */
    public static void removeGui(IncusClient incus, String name) {
        // Every branch without --gui comes through here, and most templates never had GUI:
        // one read then, instead of a device rewrite, a config write and two file pushes.
        if (!hasGui(incus.instanceMetadata(name))) return;

        incus.devicesRemoveAll(name, GUI_DEVICES);

        var configUnsets = new java.util.LinkedHashMap<String, Object>();
        configUnsets.put(Metadata.GUI_ENABLED, null);
        configUnsets.put("environment.WAYLAND_DISPLAY", null);
        configUnsets.put("environment.XDG_RUNTIME_DIR", null);
        for (var key : WAYLAND_ENV.keySet()) {
            configUnsets.put("environment." + key, null);
        }
        incus.configUpdate(name, configUnsets);

        // Remove profile.d and tmpfiles.d scripts that re-export Wayland env in login shells
        try {
            pushTempFile(incus, name, "", "/etc/profile.d/wayland.sh");
            pushTempFile(incus, name, "", "/etc/tmpfiles.d/wayland-runtime.conf");
        } catch (IOException | RuntimeException e) {
            // Best-effort: files may not exist if GUI was never fully configured
        }
    }

    /**
     * Whether an instance carries any GUI passthrough state for {@link #removeGui} to clear.
     * {@link #configureGui} sets {@code environment.WAYLAND_DISPLAY} before it pushes the
     * profile.d/tmpfiles.d scripts, so those scripts never exist without the config keys.
     */
    static boolean hasGui(JqValue instance) {
        var devices = instance.path("devices");
        for (var device : GUI_DEVICES) {
            if (devices.has(device)) return true;
        }
        var config = instance.path("config");
        if (config.has(Metadata.GUI_ENABLED) || config.has("environment.WAYLAND_DISPLAY")
                || config.has("environment.XDG_RUNTIME_DIR")) {
            return true;
        }
        for (var key : WAYLAND_ENV.keySet()) {
            if (config.has("environment." + key)) return true;
        }
        return false;
    }

    /**
     * Warn at shell entry if a GUI-enabled container can't reach the host
     * Wayland compositor.
     */
    public static void checkGuiHealth(IncusClient incus, String name) {
        var guiEnabled = incus.configGet(name, Metadata.GUI_ENABLED);
        if (!"true".equals(guiEnabled)) return;
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        if (waylandDisplay == null || xdgRuntimeDir == null) {
            System.err.println(BuildOutput.styled(BuildOutput.YELLOW, "Warning: GUI passthrough is enabled but no Wayland session detected."));
            System.err.println("GUI applications will not work in this session.");
            return;
        }
        var socket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(socket))) {
            System.err.println(BuildOutput.styled(BuildOutput.YELLOW, "Warning: GUI passthrough is enabled but Wayland socket not found."));
            System.err.println("GUI applications may not work. Try re-branching with --gui.");
        }
    }

    private static boolean pushWaylandFiles(IncusClient incus, String container,
                                             String waylandSocketPath, String uid) {
        try {
            var profile = new StringBuilder();
            profile.append("export WAYLAND_DISPLAY=").append(waylandSocketPath).append('\n');
            profile.append("export XDG_RUNTIME_DIR=/run/user/").append(uid).append('\n');
            WAYLAND_ENV.forEach((k, v) -> profile.append("export ").append(k).append('=').append(v).append('\n'));
            pushTempFile(incus, container, profile.toString(), "/etc/profile.d/wayland.sh");

            // systemd-logind may not create /run/user/<uid> in containers;
            // ensure it exists at boot so XDG_RUNTIME_DIR is usable.
            var tmpfiles = "d /run/user/" + uid + " 0700 " + uid + " " + uid + " -\n";
            pushTempFile(incus, container, tmpfiles, "/etc/tmpfiles.d/wayland-runtime.conf");
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("Warning: failed to push wayland config: " + e.getMessage());
            return false;
        }
    }

    private static void pushTempFile(IncusClient incus, String container, String content, String destPath)
            throws IOException {
        var tmp = Files.createTempFile("isx-", ".tmp");
        try {
            Files.writeString(tmp, content);
            var perms = Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ);
            Files.setPosixFilePermissions(tmp, perms);
            incus.filePush(tmp.toString(), container, destPath);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
