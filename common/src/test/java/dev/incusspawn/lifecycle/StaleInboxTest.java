package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * An {@code --inbox} directory deleted while its instance is stopped must not fail the next start
 * with Incus's {@code Missing source path} (#854), and comes back once the directory does: isx
 * mounts it again itself rather than naming an {@code incus config device add} the macOS host could
 * not run (#1139).
 */
class StaleInboxTest {

    private static final String NAME = "dev-1";

    @BeforeEach
    void linuxSourcesOnly() {
        // On macOS the device source is the appliance VM's /host view of the path.
        assumeFalse(Platform.isMacOS());
    }

    private static FakeIncusDaemon withInbox(Path source) {
        // Filtering on, as isx branch leaves it, so the pre-start repair has only the inbox to do.
        return new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true")
                .device(NAME, InstanceLifecycle.INBOX_DEVICE, Map.of("type", "disk",
                        "source", source.toString(), "path", InstanceLifecycle.INBOX_PATH, "readonly", "true"));
    }

    @Test
    void inboxWhoseDirectoryIsGoneIsRemovedBeforeStart(@TempDir Path tmp) throws Exception {
        var deleted = tmp.resolve("deleted");
        var daemon = withInbox(deleted);

        // The overload without an output reports to the bound one
        var out = RecordingOutput.during(() -> InstanceLifecycle.prepareForStart(daemon.client(), NAME));
        var said = String.join("\n", out.warnings());

        assertTrue(daemon.instance(NAME).path("devices").path(InstanceLifecycle.INBOX_DEVICE).isMissingNode(),
                daemon.instance(NAME).path("devices").toString());
        assertEquals(deleted.toString(),
                daemon.instance(NAME).path("config").path(Metadata.INBOX_SOURCE).asText());
        assertTrue(said.contains("recreate it; the next start of " + NAME + " does"), said);
        assertFalse(said.contains("incus "), said);
    }

    @Test
    void inboxIsMountedAgainOnceItsDirectoryIsBack(@TempDir Path tmp) throws Exception {
        var inbox = tmp.resolve("inbox");
        var daemon = withInbox(inbox);
        var said = new java.util.ArrayList<String>();
        InstanceLifecycle.prepareForStart(daemon.client(), NAME, RecordingOutput.into(said));

        Files.createDirectories(inbox);
        said.clear();
        InstanceLifecycle.prepareForStart(daemon.client(), NAME, RecordingOutput.into(said));

        var device = daemon.instance(NAME).path("devices").path(InstanceLifecycle.INBOX_DEVICE);
        assertEquals("disk", device.path("type").asText(), device.toString());
        assertEquals(inbox.toString(), device.path("source").asText());
        assertEquals(InstanceLifecycle.INBOX_PATH, device.path("path").asText());
        assertEquals("true", device.path("readonly").asText());
        assertTrue(daemon.instance(NAME).path("config").path(Metadata.INBOX_SOURCE).isMissingNode(),
                daemon.instance(NAME).path("config").toString());
        assertEquals(1, said.size(), said.toString());
        assertTrue(said.get(0).contains("Inbox mounted again: " + inbox), said.get(0));
    }

    @Test
    void inboxStillMissingStaysRememberedAndQuiet(@TempDir Path tmp) {
        var daemon = withInbox(tmp.resolve("deleted"));
        var said = new java.util.ArrayList<String>();
        InstanceLifecycle.prepareForStart(daemon.client(), NAME, RecordingOutput.into(said));
        said.clear();

        InstanceLifecycle.prepareForStart(daemon.client(), NAME, RecordingOutput.into(said));

        assertEquals(java.util.List.of(), said, "the warning was given once, at the removal");
        assertTrue(daemon.instance(NAME).path("devices").path(InstanceLifecycle.INBOX_DEVICE).isMissingNode());
        assertFalse(daemon.instance(NAME).path("config").path(Metadata.INBOX_SOURCE).isMissingNode());
    }

    /** One warning, its advice as a detail, on the output the caller hands it (the TUI's log). */
    @Test
    void theWarningIsOneWarningWithItsAdviceOnTheCallersOutput(@TempDir Path tmp) {
        var daemon = withInbox(tmp.resolve("deleted"));
        var out = new RecordingOutput();

        // Only there: the bound output, the terminal under the TUI, hears nothing
        var bound = RecordingOutput.during(() -> InstanceLifecycle.prepareForStart(daemon.client(), NAME, out));

        assertEquals(List.of(), bound.events());
        var warned = out.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warned.size(), out.events()::toString);
        assertTrue(warned.getFirst().msg().startsWith("inbox directory not found: "), warned.getFirst()::msg);
        assertEquals(List.of("To mount it again, recreate it; the next start of " + NAME + " does."),
                warned.getFirst().details());
    }

    @Test
    void healthyInboxIsKeptAtNoExtraRequest(@TempDir Path tmp) throws Exception {
        var daemon = withInbox(Files.createDirectories(tmp.resolve("inbox")));

        InstanceLifecycle.prepareForStart(daemon.client(), NAME);

        assertFalse(daemon.instance(NAME).path("devices").path(InstanceLifecycle.INBOX_DEVICE).isMissingNode());
        assertEquals(1, daemon.requests().size(), String.join("\n", daemon.requests()));
    }
}
