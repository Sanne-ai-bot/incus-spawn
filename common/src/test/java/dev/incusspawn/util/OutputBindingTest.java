package dev.incusspawn.util;

import dev.incusspawn.Warnings;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where each kind of message goes under each binding the owner of the terminal makes (#1703). */
class OutputBindingTest {

    /** What a terminal was asked to print, by kind. */
    private static final class Recorder implements Output {
        final List<String> events = new ArrayList<>();

        @Override public void warn(String msg, String... details) { events.add("warn " + msg); }
        @Override public void warnAside(String msg) { events.add("warnAside " + msg); }
        @Override public void stepWarn(String msg) { events.add("stepWarn " + msg); }
        @Override public void warnBanner(String title, String... lines) { events.add("warnBanner " + title); }
        @Override public void stepFail(String msg) { events.add("stepFail " + msg); }
        @Override public void note(String msg) { events.add("note " + msg); }
        @Override public void step(String msg) { events.add("step " + msg); }
    }

    private static void reportEveryKind() {
        BuildOutput.step("Copying");
        BuildOutput.warn("a warning", "what to do");
        BuildOutput.stepWarn("a step's warning");
        BuildOutput.warnBanner("CA mismatch", "rebuild the template");
        BuildOutput.stepFail("a failed step");
        BuildOutput.note("a note");
        Warnings.warn("a definition's warning");
    }

    /**
     * A branch or a build the TUI runs on its released terminal, which it then draws over: every
     * warning is printed and kept in the log, not only {@code BuildOutput.warn} (#1646, #1690). A
     * failed step is printed only: its caller reports the failure on the status line.
     */
    @Test
    void aReleasedTerminalPrintsEveryDiagnosticAndLogsIt() {
        var terminal = new Recorder();
        var log = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.copying(terminal, Output.diagnosticsTo(log::add)))) {
            reportEveryKind();
        }

        assertEquals(List.of("a warning\nwhat to do", "a step's warning", "CA mismatch\nrebuild the template",
                "a definition's warning"), log);
        assertEquals(List.of("step Copying", "warn a warning", "stepWarn a step's warning",
                "warnBanner CA mismatch", "stepFail a failed step", "note a note",
                "warnAside a definition's warning"), terminal.events);
    }

    /** While the TUI draws, a line on the terminal is drawn over: every warning goes to its log, the rest nowhere. */
    @Test
    void whileTheTuiDrawsNothingReachesTheTerminal() {
        var log = new ArrayList<String>();
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var previousOut = System.out;
        var previousErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(log::add))) {
            BuildOutput.header("Starting dev-1");
            try (var group = BuildOutput.group("Tools", "maven-3")) {
                BuildOutput.stepStart("Waiting for the guest...");
                BuildOutput.stepDone();
            }
            reportEveryKind();
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }

        assertEquals("", out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("a warning\nwhat to do", "a step's warning", "CA mismatch\nrebuild the template",
                "a failed step", "a definition's warning"), log);
    }

    /**
     * Before the TUI opens, what {@code Warnings} reports is held for its log, while a warning
     * about a step, like the step, prints as it happens.
     */
    @Test
    void beforeTheTuiOpensNoticesAreHeldAndStepsPrinted() {
        var terminal = new Recorder();
        var held = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.holding(terminal, Output.diagnosticsTo(held::add)))) {
            reportEveryKind();
        }

        assertEquals(List.of("a definition's warning"), held);
        assertEquals(List.of("step Copying", "warn a warning", "stepWarn a step's warning",
                "warnBanner CA mismatch", "stepFail a failed step", "note a note"), terminal.events);

        Output.holding(terminal, Output.diagnosticsTo(held::add)).warnAside("asked directly");
        assertEquals("asked directly", held.getLast());
        assertEquals("note a note", terminal.events.getLast());
    }

    /**
     * A build the TUI runs copies its warnings into the log the TUI draws from: a definition
     * warning that log already holds is printed for the build, not added to the log again.
     */
    @Test
    void aCopyRemembersWhatItsLogAlreadyReported() {
        var terminal = new Recorder();
        var log = new ArrayList<String>();
        var drawing = Output.diagnosticsTo(log::add);
        Warnings.warn(drawing, "broken tool file");
        try (var ignored = BuildOutput.bind(Output.copying(terminal, drawing))) {
            Warnings.warn("broken tool file");
            Warnings.warn("broken tool file");
        }

        assertEquals(List.of("broken tool file"), log);
        assertEquals(List.of("warnAside broken tool file"), terminal.events);
    }

    /** A method {@code Output} gains is forwarded, or the TUI's lent terminal and the CLI's bound output would silently lose it. */
    @Test
    void everyDecoratorForwardsEveryMethodOfOutput() throws Exception {
        for (var method : Output.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            var declared = ForwardingOutput.class.getMethod(method.getName(), method.getParameterTypes());
            assertEquals(ForwardingOutput.class, declared.getDeclaringClass(), method.toString());
        }
        // The copying and holding terminals, and BuildOutput.bound(), forward through it
        assertTrue(Output.copying(BuildOutput.terminal(), Output.diagnosticsTo(m -> {})) instanceof ForwardingOutput);
        assertTrue(BuildOutput.bound() instanceof ForwardingOutput);
    }

    @Test
    void bindingsClosedOutOfOrderAreRefused() {
        var outer = BuildOutput.bind(Output.diagnosticsTo(m -> {}));
        var inner = BuildOutput.bind(Output.diagnosticsTo(m -> {}));
        try {
            assertThrows(IllegalStateException.class, outer::close);
        } finally {
            inner.close();
            outer.close();
        }
        assertEquals(BuildOutput.terminal(), BuildOutput.current());
    }

    /**
     * A query command's stdout holds only its results: a definition warning raised while it loads
     * goes to stderr alone, without the blank line that ends a command's own warning on stdout.
     */
    @Test
    void aWarningFromCodeWithNoCallerKeepsStdoutClean() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var previousOut = System.out;
        var previousErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Warnings.forgetReported(BuildOutput.terminal());
        try {
            Warnings.warn("broken tool file, seen by " + getClass().getName());
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }

        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("⚠ broken tool file"), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void closingABindingPutsThePreviousOneBack() {
        var outer = new ArrayList<String>();
        var inner = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(outer::add))) {
            try (var nested = BuildOutput.bind(Output.diagnosticsTo(inner::add))) {
                BuildOutput.warn("inner");
            }
            BuildOutput.warn("outer");
        }

        assertEquals(List.of("inner"), inner);
        assertEquals(List.of("outer"), outer);
        assertEquals(BuildOutput.terminal(), BuildOutput.current());
    }

    /** What a log shows is made safe, since a warning can quote template text. */
    @Test
    void aLoggedMessageCannotDriveTheTerminal() {
        var log = new ArrayList<String>();
        Output.diagnosticsTo(log::add).warn("Warning: tool \u001b]0;title\u0007x", "line one\nline two");

        var lines = log.get(0).lines().toList();
        assertEquals(3, lines.size(), log.get(0));
        assertTrue(lines.get(0).startsWith("tool "), lines.get(0));
        assertEquals(List.of("line one", "line two"), lines.subList(1, 3));
        assertTrue(log.get(0).chars().noneMatch(c -> c == 0x1b || c == 0x07), log.get(0));
    }
}
