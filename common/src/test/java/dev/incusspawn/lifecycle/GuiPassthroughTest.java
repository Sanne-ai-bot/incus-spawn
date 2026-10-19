package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.FakeIncusDaemon;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GuiPassthrough#hasGui} must recognise every GUI device a source may carry over, so a
 * branch made without {@code --gui} from a GUI source has its passthrough cleared. That includes
 * the per-socket devices this isx adds and the whole-directory {@code xdg-runtime} mount an older
 * one left, since a non-GUI branch of either must not keep host sockets mounted.
 */
class GuiPassthroughTest {

    private static JqValue instance(String devices, String config) {
        return JqValues.parse("{\"devices\":" + devices + ",\"config\":" + config + "}");
    }

    @Test
    void detectsGpuDevice() {
        assertTrue(GuiPassthrough.hasGui(instance("{\"gpu\":{\"type\":\"gpu\"}}", "{}")));
    }

    @Test
    void detectsWaylandSocketDevice() {
        assertTrue(GuiPassthrough.hasGui(instance(
                "{\"xdg-wayland\":{\"type\":\"disk\",\"path\":\"/mnt/host-xdg/wayland-0\"}}", "{}")));
    }

    @Test
    void detectsAudioSocketDevices() {
        assertTrue(GuiPassthrough.hasGui(instance("{\"xdg-pipewire\":{\"type\":\"disk\"}}", "{}")));
        assertTrue(GuiPassthrough.hasGui(instance("{\"xdg-pulse\":{\"type\":\"disk\"}}", "{}")));
    }

    @Test
    void detectsLegacyWholeDirectoryMount() {
        // A branch made by an older isx carries an xdg-runtime device; removeGui must still clear it.
        assertTrue(GuiPassthrough.hasGui(instance(
                "{\"xdg-runtime\":{\"type\":\"disk\",\"path\":\"/mnt/host-xdg\"}}", "{}")));
    }

    @Test
    void detectsEnvironmentKeysWithoutDevices() {
        assertTrue(GuiPassthrough.hasGui(instance("{}",
                "{\"environment.WAYLAND_DISPLAY\":\"/mnt/host-xdg/wayland-0\"}")));
    }

    @Test
    void ignoresInstanceWithoutGui() {
        assertFalse(GuiPassthrough.hasGui(instance(
                "{\"eth0\":{\"type\":\"nic\",\"network\":\"incusbr0\"}}", "{}")));
    }

    /**
     * The isolation property: GUI passthrough mounts the Wayland and audio sockets on their own,
     * never the host's whole XDG_RUNTIME_DIR, and drops a carried-over whole-directory mount. This
     * fails if that directory mount is ever reintroduced (a device whose source is the directory),
     * if a sensitive socket is mounted, or if the carried-over device is left in place.
     */
    @Test
    void mountsOnlySocketsNotTheWholeRuntimeDir(@TempDir Path xdg) throws IOException {
        // A host XDG_RUNTIME_DIR with the GUI/audio sockets and sensitive ones that must stay out.
        for (var socket : List.of("wayland-0", "pipewire-0", "pipewire-0-manager", "bus", "ssh-agent.socket")) {
            Files.createFile(xdg.resolve(socket));
        }
        Files.createDirectory(xdg.resolve("pulse"));
        Files.createFile(xdg.resolve("pulse/native"));

        var daemon = new FakeIncusDaemon().container("gui", Map.of());
        // A whole-directory mount an older isx left on the source must be removed, not kept.
        daemon.device("gui", "xdg-runtime",
                Map.of("type", "disk", "source", "/run/user/1000", "path", "/mnt/host-xdg"));

        daemon.client().updateFresh("gui", GuiPassthrough.guiUpdate(xdg.toString(), "wayland-0", "1000",
                GuiPassthrough.presentAudioSockets(xdg.toString())));

        var devices = daemon.instanceDevices("gui");
        // Only the GPU and the present safe sockets — no directory mount, no manager/D-Bus/ssh-agent.
        assertEquals(Set.of("gpu", "xdg-wayland", "xdg-pipewire", "xdg-pulse"), devices.keySet());
        assertFalse(devices.containsKey("xdg-runtime"), "the carried-over whole-directory mount must be removed");
        for (var device : devices.values()) {
            var source = device.get("source");
            if (source == null) continue;
            assertNotEquals(xdg.toString(), source, "a device mounts the whole XDG_RUNTIME_DIR");
            assertFalse(source.endsWith("/bus") || source.endsWith("/ssh-agent.socket")
                            || source.endsWith("pipewire-0-manager"),
                    "a sensitive socket is mounted: " + source);
        }
        assertEquals(xdg + "/pipewire-0", devices.get("xdg-pipewire").get("source"));
        assertEquals("/mnt/host-xdg/pulse/native", devices.get("xdg-pulse").get("path"));
        assertEquals("false", devices.get("xdg-wayland").get("required"));
        // One read and one write (a PUT, since the carried-over device is removed).
        assertEquals(2, daemon.requests().size(), "GUI config should be a single read-modify-write");
    }
}
