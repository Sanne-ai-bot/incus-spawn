package dev.incusspawn.config;

import dev.incusspawn.Platform;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A project-local template's host-resource source comes from a cloned repository, so the build
 * and start notes that quote it show it on one line that cannot drive a terminal (#1235, #1133).
 */
class HostResourceNotesTest {

    /** A line break that would forge an isx line, and an escape that would clear the screen. */
    private static final String HOSTILE = "data\nTemplate is valid.\u001b[2J";

    @TempDir
    Path tmp;

    private Path project;
    private MockedStatic<Platform> platform;
    private final IncusClient incus = mock(IncusClient.class);
    private final Container build = new Container(incus, "b");

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        platform = mockStatic(Platform.class, CALLS_REAL_METHODS);
        host(false);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(new IncusClient.ExecResult(0, "", ""));
    }

    private void host(boolean macOs) {
        platform.when(Platform::isMacOS).thenReturn(macOs);
        platform.when(Platform::isLinux).thenReturn(!macOs);
    }

    @AfterEach
    void releasePlatform() {
        platform.close();
    }

    @Test
    void mountedNoteQuotesTheSourceOnOneLine() throws IOException {
        Files.createDirectories(project.resolve(HOSTILE));
        var hr = collect("readonly");
        assertQuoted(stderrOf(() -> HostResourceSetup.applyForBuild(incus, build,
                List.of(hr), MachineType.CONTAINER)), "Mounted ");
        assertQuoted(stderrOf(() -> HostResourceSetup.applyForInstance(incus, "i", List.of(hr),
                MachineType.CONTAINER)), "Mounted ");
    }

    @Test
    void copiedNoteQuotesTheSourceOnOneLine() throws IOException {
        Files.createDirectories(project.resolve(HOSTILE));
        assertQuoted(stderrOf(() -> HostResourceSetup.applyForBuild(incus, build,
                List.of(collect("copy")), MachineType.CONTAINER)), "Copied ");
    }

    @Test
    void overlayNoteQuotesTheSourceOnOneLine() throws IOException {
        Files.createDirectories(project.resolve(HOSTILE));
        assertQuoted(stderrOf(() -> HostResourceSetup.applyForBuild(incus, build,
                List.of(collect("overlay")), MachineType.CONTAINER)), "Mounted ");
    }

    @Test
    void missingSourceWarningQuotesTheSourceOnOneLine() {
        for (var mode : List.of("readonly", "copy", "overlay")) {
            assertQuoted(stderrOf(() -> HostResourceSetup.applyForBuild(incus, build,
                    List.of(collect(mode)), MachineType.CONTAINER)), "Warning: host-resource source not found: ");
        }
        assertQuoted(stderrOf(() -> HostResourceSetup.applyForInstance(incus, "i", List.of(collect("readonly")),
                MachineType.CONTAINER)), "Warning: host-resource source not found: ");
    }

    @Test
    void staleDeviceWarningQuotesTheSourceOnOneLine() {
        var json = HostResourceSetup.serialize(List.of(collect("readonly")));
        var metadata = io.hyperfoil.tools.jjq.value.JqObject.builder(1)
                .put("config", io.hyperfoil.tools.jjq.value.JqObject.builder(1).put(Metadata.HOST_RESOURCES, json).build())
                .build();
        var warnings = new ArrayList<String>();
        HostResourceSetup.removeStaleDevices(incus, "i", metadata, warnings::add);
        assertQuoted(String.join("\n", warnings) + "\n", "Warning: host-resource source not found: ");
    }

    @Test
    void macOsOverlayRefusalQuotesTheSourceOnOneLine() throws IOException {
        host(true);
        Files.createDirectories(project.resolve(HOSTILE));
        var e = assertThrows(IllegalStateException.class, () -> HostResourceSetup.applyForBuild(incus,
                build, List.of(collect("overlay")), MachineType.VM));
        assertEquals(4, e.getMessage().lines().count(), e.getMessage());
        assertTrue(e.getMessage().contains("data Template is valid. [2J' uses overlay mode"), e.getMessage());
    }

    /** One line of {@code out} starts with {@code prefix} and quotes the source; none is forged. */
    private static void assertQuoted(String out, String prefix) {
        assertFalse(out.contains("\u001b[2J"), out);
        assertTrue(out.lines().noneMatch(l -> l.startsWith("Template is valid.")), out);
        assertTrue(out.lines().anyMatch(l -> l.strip().startsWith(prefix)
                && l.contains("data Template is valid. [2J")), out);
    }

    private static String stderrOf(Runnable action) {
        var err = new ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new PrintStream(err, true));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return err.toString();
    }

    private ImageDef.HostResource collect(String mode) {
        var def = new ImageDef();
        def.setName("tpl-project");
        def.setSource(project.resolve(".incus-spawn/images/project.yaml").toString());
        def.setProjectRoot(project);
        def.setHostResources(List.of(new ImageDef.HostResource(HOSTILE, "/home/agentuser/data", mode)));
        return HostResourceSetup.collectEffective(def, Map.of("tpl-project", def)).getFirst();
    }
}
