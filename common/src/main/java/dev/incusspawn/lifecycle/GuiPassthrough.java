package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
     * An audio socket GUI mounts when the host has it: its device name, its path in
     * {@code XDG_RUNTIME_DIR}, and the variable that points clients at it. Clients look for their
     * socket in the guest's own {@code XDG_RUNTIME_DIR}, so without the variable they never find the
     * one mounted under {@code /mnt/host-xdg}.
     */
    record AudioSocket(String device, String relPath, String envKey, String envPrefix) {
        String envValue() {
            return envPrefix + MOUNT_ROOT + "/" + relPath;
        }
    }

    /**
     * The audio sockets GUI mounts when the host has them. Only these and the Wayland display are
     * passed through, never the whole {@code XDG_RUNTIME_DIR}: that directory also holds the session
     * D-Bus, podman, ssh-agent and keyring sockets, all owned by the host user whose uid the guest
     * shares, so a mount of it would hand the guest the host. PipeWire's {@code pipewire-0-manager}
     * is left out on purpose: a client on it gets session-manager rights (screencast and camera
     * nodes, rerouting), which playback does not need.
     */
    private static final List<AudioSocket> AUDIO_SOCKETS = List.of(
            new AudioSocket("xdg-pipewire", "pipewire-0", "PIPEWIRE_REMOTE", ""),
            new AudioSocket("xdg-pulse", "pulse/native", "PULSE_SERVER", "unix:"));

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
        AUDIO_SOCKETS.forEach(socket -> names.add(socket.device()));
        return List.copyOf(names);
    }

    /**
     * Every environment key {@link #guiEnv} may set, so {@link #guiUpdate} unsets the ones it does
     * not set this time, and {@link #removeGui} and {@link #hasGui} clear and detect them.
     */
    private static final List<String> GUI_ENV_KEYS = guiEnvKeys();

    private static List<String> guiEnvKeys() {
        var keys = new ArrayList<String>();
        keys.add("WAYLAND_DISPLAY");
        keys.add("XDG_RUNTIME_DIR");
        keys.addAll(WAYLAND_ENV.keySet());
        AUDIO_SOCKETS.forEach(socket -> keys.add(socket.envKey()));
        return List.copyOf(keys);
    }

    /**
     * Why GUI passthrough cannot work from a session with {@code env}, on a host where {@code exists}
     * says which paths exist, or null when it can.
     */
    static Unavailable waylandProblem(Map<String, String> env, java.util.function.Predicate<String> exists) {
        var xdgRuntimeDir = env.get("XDG_RUNTIME_DIR");
        var waylandDisplay = env.get("WAYLAND_DISPLAY");
        if (xdgRuntimeDir == null || waylandDisplay == null) {
            return new Unavailable("GUI passthrough requires WAYLAND_DISPLAY and XDG_RUNTIME_DIR.", IN_A_WAYLAND_SESSION);
        }
        if (!WAYLAND_DISPLAY_PATTERN.matcher(waylandDisplay).matches()) {
            return new Unavailable("WAYLAND_DISPLAY contains invalid characters: " + waylandDisplay);
        }
        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!exists.test(hostSocket)) {
            return new Unavailable("Wayland socket not found at " + hostSocket, IN_A_WAYLAND_SESSION);
        }
        return null;
    }

    private static final String IN_A_WAYLAND_SESSION = "Make sure you are running isx from a Wayland graphical session.";

    /**
     * Configure GUI passthrough on a (stopped) container: GPU device, Wayland
     * socket mount, environment variables, and tmpfiles.d for XDG_RUNTIME_DIR.
     *
     * @return why it could not, for the caller to warn of as one warning with what it does
     *         instead (#1690); null when it could
     */
    public static Unavailable configureGui(IncusClient incus, String name, Map<String, String> env,
                                           java.util.function.Predicate<String> exists) {
        var problem = waylandProblem(env, exists);
        if (problem != null) return problem;
        var xdgRuntimeDir = env.get("XDG_RUNTIME_DIR");
        var waylandDisplay = env.get("WAYLAND_DISPLAY");

        BuildOutput.step("Enabling GUI passthrough.");
        var uid = InstanceLifecycle.getUid();
        // Checked once, so the instance config and the profile script agree on what is mounted.
        var audio = presentAudioSockets(xdgRuntimeDir);
        incus.updateFresh(name, guiUpdate(xdgRuntimeDir, waylandDisplay, uid, audio));

        var pushFailure = pushWaylandFiles(incus, name, profileScript(guiEnv(waylandDisplay, uid, audio)), uid);
        // A cause alone: the branch then removes the devices, so there is no GUI left to advise on
        if (pushFailure != null) return new Unavailable("could not install the GUI profile.d scripts: " + pushFailure);
        return null;
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
    static InstanceUpdate guiUpdate(String xdgRuntimeDir, String waylandDisplay, String uid,
                                     List<AudioSocket> audio) {
        var update = new InstanceUpdate();
        var mounted = new HashSet<String>();
        update.replaceDevice("gpu", Map.of("type", "gpu"));
        mounted.add("gpu");
        // The Wayland display (already checked to exist by waylandProblem), plus each audio socket
        // the host has: a missing one is skipped rather than mounted as an empty path.
        mountSocket(update, mounted, xdgRuntimeDir, WAYLAND_DEVICE, waylandDisplay);
        for (var socket : audio) {
            mountSocket(update, mounted, xdgRuntimeDir, socket.device(), socket.relPath());
        }
        // Env via container config (seen by init and direct exec); profileScript repeats it for
        // login shells, which su - resets. A key not set this time is unset: an audio variable a
        // source carried over for a socket the host now lacks would point clients at nothing.
        var env = guiEnv(waylandDisplay, uid, audio);
        GUI_ENV_KEYS.forEach(key -> update.config("environment." + key, env.get(key)));
        GUI_DEVICES.stream().filter(device -> !mounted.contains(device)).forEach(update::removeDevice);
        return update;
    }

    private static void mountSocket(InstanceUpdate update, Set<String> mounted, String xdgRuntimeDir,
                                    String device, String relPath) {
        update.replaceDevice(device, Map.of(
                "type", "disk",
                "source", xdgRuntimeDir + "/" + relPath,
                "path", MOUNT_ROOT + "/" + relPath,
                "required", "false"));
        mounted.add(device);
    }

    /** The audio sockets the host has under {@code xdgRuntimeDir}. */
    static List<AudioSocket> presentAudioSockets(String xdgRuntimeDir) {
        return AUDIO_SOCKETS.stream()
                .filter(socket -> Files.exists(Path.of(xdgRuntimeDir, socket.relPath())))
                .toList();
    }

    /** The guest environment GUI passthrough sets, for the given mounted audio sockets. */
    static Map<String, String> guiEnv(String waylandDisplay, String uid, List<AudioSocket> audio) {
        var env = new LinkedHashMap<String, String>();
        env.put("WAYLAND_DISPLAY", MOUNT_ROOT + "/" + waylandDisplay);
        env.put("XDG_RUNTIME_DIR", "/run/user/" + uid);
        env.putAll(WAYLAND_ENV);
        audio.forEach(socket -> env.put(socket.envKey(), socket.envValue()));
        return env;
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

        var configUnsets = new LinkedHashMap<String, Object>();
        configUnsets.put(Metadata.GUI_ENABLED, null);
        for (var key : GUI_ENV_KEYS) {
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
        if (config.has(Metadata.GUI_ENABLED)) return true;
        for (var key : GUI_ENV_KEYS) {
            if (config.has("environment." + key)) return true;
        }
        return false;
    }

    /**
     * Warn at shell entry if a GUI-enabled container can't reach the host
     * Wayland compositor.
     */
    public static void checkGuiHealth(IncusClient incus, String name, Output out) {
        checkGuiHealth(incus, name, System.getenv(), out);
    }

    /** As above, from a session with {@code env}: a warning with its advice, to {@code out}. */
    static void checkGuiHealth(IncusClient incus, String name, Map<String, String> env, Output out) {
        var guiEnabled = incus.configGet(name, Metadata.GUI_ENABLED);
        if (!"true".equals(guiEnabled)) return;
        var waylandDisplay = env.get("WAYLAND_DISPLAY");
        var xdgRuntimeDir = env.get("XDG_RUNTIME_DIR");
        if (waylandDisplay == null || xdgRuntimeDir == null) {
            out.warn("GUI passthrough is enabled but no Wayland session detected.",
                    "GUI applications will not work in this session.");
            return;
        }
        var socket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!Files.exists(Path.of(socket))) {
            out.warn("GUI passthrough is enabled but Wayland socket not found.",
                    "GUI applications may not work. Try re-branching with --gui.");
        }
    }

    /**
     * {@code /etc/profile.d/wayland.sh} for {@link #guiEnv}'s environment. Package-private so a
     * test can assert what login shells get.
     */
    static String profileScript(Map<String, String> env) {
        var profile = new StringBuilder();
        env.forEach((k, v) -> profile.append("export ").append(k).append('=').append(v).append('\n'));
        return profile.toString();
    }

    /** Why the files could not be pushed, or null when they were. */
    private static String pushWaylandFiles(IncusClient incus, String container, String profile, String uid) {
        try {
            pushTempFile(incus, container, profile, "/etc/profile.d/wayland.sh");

            // systemd-logind may not create /run/user/<uid> in containers;
            // ensure it exists at boot so XDG_RUNTIME_DIR is usable.
            var tmpfiles = "d /run/user/" + uid + " 0700 " + uid + " " + uid + " -\n";
            pushTempFile(incus, container, tmpfiles, "/etc/tmpfiles.d/wayland-runtime.conf");
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
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
