package dev.incusspawn.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildOutputTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        var sink = new PrintStream(captured, true);
        System.setOut(sink);
        System.setErr(sink);
    }

    @AfterEach
    void restore() {
        BuildOutput.forceAnsi = null;
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String text() {
        return captured.toString();
    }

    @Test
    void plainStepCompletesItsLine() {
        BuildOutput.stepStart("Waiting for network...");
        BuildOutput.stepDone();
        BuildOutput.stepStart("Extracting root disk...");
        BuildOutput.stepDone("4.0G");
        assertEquals("""
                    Waiting for network... done.
                    Extracting root disk... done (4.0G).
                """, text());
    }

    @Test
    void plainOutputPrintedMidStepMovesBelowTheStepLine() {
        BuildOutput.stepStart("Installing foo...");
        System.err.println("    Warning: something");
        BuildOutput.stepDone();
        assertEquals("""
                    Installing foo...
                    Warning: something
                    Installing foo... done.
                """, text());
    }

    @Test
    void streamsAreRestoredWhenTheStepEnds() {
        var out = System.out;
        var err = System.err;
        BuildOutput.stepStart("Working...");
        assertNotSame(out, System.out, "a live step guards stdout");
        BuildOutput.stepDone();
        assertSame(out, System.out);
        assertSame(err, System.err);
    }

    @Test
    void ansiStepTurnsIntoACheck() {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Deriving from parent image 'tpl-dev'...");
        BuildOutput.stepDone();
        var lines = lastLines();
        assertEquals("    ✓ Deriving from parent image 'tpl-dev'", lines.getLast());
    }

    @Test
    void ansiFailedStepTurnsIntoACross() {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Verifying DNS resolution...");
        BuildOutput.stepBreak();
        assertEquals("    ✗ Verifying DNS resolution", lastLines().getLast());
    }

    @Test
    void ansiOutputPrintedMidStepFreezesTheLineAndStopsDrawingIt() throws Exception {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Installing foo...");
        System.err.println("    ⚠ careful");
        var afterWarning = text().length();
        Thread.sleep(250); // several spinner ticks
        assertEquals(afterWarning, text().length(), "the spinner must not draw over later output");
        BuildOutput.stepDone();
        assertEquals(List.of("    … Installing foo", "    ⚠ careful", "    ✓ Installing foo"), lastLines());
    }

    @Test
    void progressDetailShowsWhileRunning() throws Exception {
        BuildOutput.forceAnsi = true;
        BuildOutput.stepStart("Installing mx...");
        BuildOutput.stepProgress("verifying: mx version");
        Thread.sleep(200);
        assertTrue(BuildOutput.stripAnsi(text()).contains("Installing mx  verifying: mx version"));
        BuildOutput.stepDone();
        assertEquals("    ✓ Installing mx", lastLines().getLast(), "the detail is gone once done");
    }

    @Test
    void groupIndentsItsStepsAndEndsWithABlankLine() {
        try (var group = BuildOutput.group("Tools", "maven-3, mx")) {
            BuildOutput.step("inside");
            BuildOutput.list(List.of("a", "b"));
        }
        BuildOutput.step("outside");
        assertEquals("""

                    ▸ Tools  maven-3, mx
                      inside
                      a, b

                    outside
                """, BuildOutput.stripAnsi(text()));
    }

    @Test
    void consecutiveGroupsAreSeparatedByOneBlankLine() {
        try (var packages = BuildOutput.group("Packages", null)) {
            BuildOutput.step("p");
        }
        try (var tools = BuildOutput.group("Tools", null)) {
            BuildOutput.step("t");
            BuildOutput.stepNote("version 1");
        }
        BuildOutput.success("built");
        assertEquals("""

                    ▸ Packages
                      p

                    ▸ Tools
                      t
                        version 1

                    ✓ built
                """, BuildOutput.stripAnsi(text()));
    }

    @Test
    void warningIsFollowedByOneBlankLine() {
        try (var tools = BuildOutput.group("Verifying", null)) {
            BuildOutput.step("mvn");
            BuildOutput.stepWarn("broken");
            BuildOutput.step("mx");
            BuildOutput.stepWarn("broken too");
        }
        BuildOutput.warn("top-level", "what to do");
        BuildOutput.step("next");
        // The blank line before the group depends on how the previous test's output ended.
        assertEquals("""
                    ▸ Verifying
                      mvn
                        ⚠ broken

                      mx
                        ⚠ broken too

                    ⚠ top-level
                      what to do

                    next
                """, BuildOutput.stripAnsi(text()).replaceFirst("^\n", ""));
    }

    @Test
    void headerResetsAGroupLeftOpenByAFailure() {
        BuildOutput.group("Packages", null);
        BuildOutput.header("Next");
        BuildOutput.step("top level");
        assertTrue(text().endsWith("\n    top level\n"));
    }

    @Test
    void plainOutputHasNoEscapes() {
        BuildOutput.forceAnsi = false;
        BuildOutput.header("Building tpl-dev");
        BuildOutput.header("Resizing", "1G → 2G");
        BuildOutput.buildHeader("tpl-dev", 1, 2);
        BuildOutput.branchHeader("dev-1", "tpl-dev");
        try (var group = BuildOutput.group("Tools", "maven-3")) {
            BuildOutput.stepNote("version 1");
            BuildOutput.stepWarn("broken");
        }
        BuildOutput.ok("verified");
        BuildOutput.note("a note");
        BuildOutput.warn("careful", "what to do");
        BuildOutput.success("built");
        BuildOutput.warnBanner("Subnet conflict", "body");
        assertEquals(BuildOutput.stripAnsi(text()), text(), "no escape off an ANSI terminal");
        assertTrue(text().contains("● Building tpl-dev"));
    }

    /** A note quotes template text, which must not start a line or drive the terminal (#1235). */
    @Test
    void noteIsOneLineThatCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.note("Host repo /p/\u001b[2Jx\u009b31m\r✓ built\nTemplate is valid.\u0007");
        BuildOutput.note("\u001b\u0007");
        assertEquals("Host repo /p/ [2Jx 31m ✓ built Template is valid. \n", text().stripLeading());
    }

    /** A step's text quotes template text too: its start, note and failure keep isx's line breaks only (#1235). */
    @Test
    void stepStartCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.stepStart("Skipping /p/\u001b[2Jx\u009b31m");
        BuildOutput.stepDone();
        assertFalse(text().contains("\u001b") || text().contains("\u009b"), text());
        assertTrue(text().contains("Skipping /p/ [2Jx 31m"), text());
    }

    @Test
    void stepNoteCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.stepNote("verify said \u001b[2Jx\nsecond line\rforged");
        assertFalse(text().contains("\u001b") || text().contains("\r"), text());
        assertTrue(text().contains("verify said  [2Jx\n") && text().contains("second line forged"), text());
    }

    @Test
    void stepFailCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.stepFail("failed at /p/\u001b[2Jx\u009b31m\nadvice stays");
        assertFalse(text().contains("\u001b") || text().contains("\u009b"), text());
        assertTrue(text().contains("failed at /p/ [2Jx 31m\nadvice stays"), text());
    }

    /** A warning keeps isx's own line breaks, but what it quotes cannot drive the terminal (#1235). */
    @Test
    void warningLinesCannotDriveTheTerminal() {
        BuildOutput.forceAnsi = false;
        BuildOutput.warn("Mount of /p/\u001b[2Jx refused.\nFix the definition.", "detail \u009b31m");
        BuildOutput.stepWarn("step /p/\u001b[2J");
        assertFalse(text().contains("\u001b") || text().contains("\u009b"), text());
        assertTrue(text().contains("Mount of /p/ [2Jx refused.\nFix the definition."), text());
    }

    /**
     * A build the TUI runs prints on the released terminal, which the TUI then redraws over: its
     * warnings are also handed to the TUI's log, but only while the TUI listens (#1195).
     */
    @Test
    void warningsAreCopiedToAListenerUntilItIsClosed() {
        var copied = new java.util.ArrayList<String>();
        try (var ignored = BuildOutput.copyWarningsTo(copied::add)) {
            BuildOutput.warn("tpl-a derives from tpl-old, which is deprecated", "use tpl-new");
        }
        BuildOutput.warn("after the build");
        assertEquals(List.of("tpl-a derives from tpl-old, which is deprecated\nuse tpl-new"), copied);
        assertTrue(text().contains("⚠ after the build"), "still printed: " + text());
    }

    @Test
    void warningsAndNotesGoToStderr() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        BuildOutput.forceAnsi = false;
        BuildOutput.step("result");
        BuildOutput.warn("careful", "what to do");
        BuildOutput.note("a note");
        BuildOutput.stepWarn("broken");
        assertEquals("    result\n\n\n", out.toString(), "stdout holds the step, and the blank line ending each warning");
        assertEquals("""
                    ⚠ careful
                      what to do
                    a note
                      ⚠ broken
                """, err.toString());
    }

    @Test
    void aHeaderAfterAWarningKeepsOneBlankLineOnStdout() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        BuildOutput.forceAnsi = false;
        BuildOutput.step("before");
        BuildOutput.warn("careful");
        BuildOutput.header("Next");
        assertEquals("    before\n\n  ● Next\n", out.toString(), "stdout alone, as in isx build >out.log");
    }

    @Test
    void elapsedTimeFormatting() {
        assertEquals("2.5s", BuildOutput.formatElapsed(2_500));
        assertEquals("41s", BuildOutput.formatElapsed(41_000));
        assertEquals("2m05s", BuildOutput.formatElapsed(125_000));
    }

    @Test
    void ellipsisIsTrimmedFromTheLabel() {
        assertEquals("Installing foo", BuildOutput.trimEllipsis("Installing foo..."));
        assertEquals("Waiting", BuildOutput.trimEllipsis("Waiting…"));
        assertEquals("Waiting for host repo refresh", BuildOutput.trimEllipsis("Waiting for host repo refresh"));
    }

    /** The rendered screen: each carriage return / erase-line rewrites the current line. */
    private List<String> lastLines() {
        var screen = new java.util.ArrayList<String>();
        var current = new StringBuilder();
        var plain = text().replace("\033[2K", "");
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == '\r') current.setLength(0);
            else if (c == '\n') { screen.add(BuildOutput.stripAnsi(current.toString())); current.setLength(0); }
            else current.append(c);
        }
        return screen;
    }

    @Test
    void guestTextCannotDriveTheTerminal() {
        // What a guest command printed is shown as text (#1588): colour dropped, a progress
        // line as the terminal leaves it after its last carriage return, other controls spaces.
        assertEquals("red\ntab\tend\n100%\ndone\n",
                BuildOutput.guestText("\033[31mred\033[0m\r\n\033]52;c;eA==\007tab\tend\n"
                        + " 10%\r 50%\r100%\r\ndone\n"));
        assertEquals(" 2J", BuildOutput.guestText("\u009b2J"));
        assertEquals("", BuildOutput.guestText("\r\r"));
        assertEquals(" ]0;unterminated", BuildOutput.guestText("\033]0;unterminated"));
        // A terminal still shows the text before a carriage return that only colour follows.
        assertEquals("npm error", BuildOutput.guestText("npm error\r\033[0m"));
        assertEquals("text", BuildOutput.guestText("\u009d0;title\u009ctext\033Pq#0\033\\"));
        // A control string never spans lines, so an unterminated one cannot swallow the log.
        assertEquals(" ]0;open\nnext ", BuildOutput.guestText("\033]0;open\nnext\007"));
        assertSame("plain\ttext\n", BuildOutput.guestText("plain\ttext\n"));
    }
}
