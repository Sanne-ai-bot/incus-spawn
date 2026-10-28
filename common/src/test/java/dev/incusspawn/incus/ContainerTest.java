package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    /** Return the `sh -c` script Container handed to the client. */
    private static String scriptFor(IncusClient incus) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus).shellExec(eq("test"), eq("sh"), eq("-c"), captor.capture());
        return captor.getValue();
    }

    private static IncusClient mockClient() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        return incus;
    }

    /** The body sits between the first newline and the final delimiter line. */
    private static String bodyOf(String script) {
        var start = script.indexOf('\n') + 1;
        var lastNewline = script.lastIndexOf('\n');
        return script.substring(start, lastNewline);
    }

    @Test
    void ordinaryContentUsesThePlainMarker() {
        var incus = mockClient();
        new Container(incus, "test").writeFile("/etc/thing.conf", "key = value");

        var script = scriptFor(incus);
        assertTrue(script.contains("<< 'INCUS_EOF'\n"));
        assertTrue(script.endsWith("\nINCUS_EOF"));
        assertEquals("key = value", bodyOf(script));
    }

    @Test
    void contentContainingTheMarkerIsWrittenVerbatim() {
        // Content reaching writeFile is often user-authored (tool files:, template env,
        // skills, agent notes). A fixed delimiter would let the content close the
        // heredoc early, truncating the file and running the rest as shell.
        var incus = mockClient();
        var content = "before\nINCUS_EOF\nafter";
        new Container(incus, "test").writeFile("/etc/thing.conf", content);

        var script = scriptFor(incus);
        assertTrue(script.contains("<< 'INCUS_EOF_'\n"), "delimiter should be extended");
        assertEquals(content, bodyOf(script), "content must survive byte-for-byte");
    }

    @Test
    void markerIsExtendedUntilItIsUnambiguous() {
        var incus = mockClient();
        var content = "a\nINCUS_EOF\nb\nINCUS_EOF_\nc";
        new Container(incus, "test").writeFile("/etc/thing.conf", content);

        var script = scriptFor(incus);
        assertTrue(script.contains("<< 'INCUS_EOF__'\n"));
        assertEquals(content, bodyOf(script));
    }

    @Test
    void markerMentionedInlineDoesNotTriggerExtension() {
        // Only a line equal to the delimiter closes a heredoc.
        var incus = mockClient();
        new Container(incus, "test").writeFile("/etc/thing.conf", "the marker is INCUS_EOF here");

        var script = scriptFor(incus);
        assertTrue(script.contains("<< 'INCUS_EOF'\n"));
        assertEquals("the marker is INCUS_EOF here", bodyOf(script));
    }

    @Test
    void heredocDeliversContentVerbatimToARealShell() throws Exception {
        // The post-start setup script feeds SSH keys and terminfo through this, so check what a
        // shell actually receives rather than the text alone.
        var content = "plain\nINCUS_EOF\n$HOME `id` \\E[m 'quoted' \"double\"\n\tindented";
        var process = new ProcessBuilder("sh", "-c", Container.heredoc("cat", content)).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor());
        assertEquals(content + "\n", output);
    }

    @Test
    void quietRunsShowAFailedCommandsOutputAsText() {
        // A tool's run: step decides what it prints (#1588).
        var hostile = new IncusClient.ExecResult(1, "\033]52;c;eA==\007out\n", "\033[2Jerr\n");
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), org.mockito.ArgumentMatchers.<String>any())).thenReturn(hostile);
        when(incus.execInContainer("test", "agentuser", "setup")).thenReturn(hostile);
        var recorded = new dev.incusspawn.util.RecordingOutput();
        var container = new Container(incus, "test", recorded);
        org.junit.jupiter.api.Assertions.assertThrows(IncusException.class,
                () -> container.runQuiet("failed", "setup"));
        org.junit.jupiter.api.Assertions.assertThrows(IncusException.class,
                () -> container.runAsUserQuiet("agentuser", "setup", "failed"));
        // The caller's output gets it, as one warning with the output as text under it
        var once = new dev.incusspawn.util.RecordingOutput.Event(dev.incusspawn.util.RecordingOutput.Kind.WARN,
                "The failed command printed:", "out", "err");
        assertEquals(java.util.List.of(once, once), recorded.events());
    }

    /** A verbose failure shows its end, where it says why, not thousands of lines in one warning. */
    @Test
    void aLongFailedOutputShowsItsLastLines() {
        var output = new StringBuilder();
        for (int i = 1; i <= 100; i++) output.append("line ").append(i).append('\n');
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), org.mockito.ArgumentMatchers.<String>any()))
                .thenReturn(new IncusClient.ExecResult(1, output.toString(), ""));
        var recorded = new dev.incusspawn.util.RecordingOutput();
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(IncusException.class,
                () -> new Container(incus, "test", recorded).runQuiet("failed", "setup"));
        // The failure report keeps it all, from the exception the build is handed
        assertEquals("The failed command printed:\n" + output + "\n", thrown.stepOutput());
        var details = recorded.events().getFirst().details();
        assertEquals(dev.incusspawn.util.BuildOutput.FAILED_OUTPUT_LINES + 1, details.size());
        assertEquals("(60 earlier lines not shown)", details.getFirst());
        assertEquals("line 61", details.get(1));
        assertEquals("line 100", details.getLast());
    }

    @Test
    void aCommandThatRanIsNeverRecordedAgainAsFailedWhenRecordingThrows() {
        // #1742 review: a log that throws after a successful exec must not record it as exit -1
        var incus = org.mockito.Mockito.mock(IncusClient.class);
        org.mockito.Mockito.when(incus.execInContainer("c", "agentuser", "true")).thenReturn(new IncusClient.ExecResult(0, "", ""));
        var codes = new java.util.ArrayList<Integer>();
        var container = new Container(incus, "c", dev.incusspawn.util.RecordingOutput.QUIET, (step, command, result, took) -> {
            codes.add(result.exitCode());
            throw new IllegalStateException("disk full");
        });
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> container.shDeclaredAsUser("tool 'x' verify", "agentuser", "true"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(0), codes);
    }
}
