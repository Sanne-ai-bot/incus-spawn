package dev.incusspawn.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A single Ctrl+C, which may be a terminal's copy shortcut, does not end a running build (#1415). */
class BuildInterruptTest {

    private static final List<String> RESET_SIGINT = List.of("env", "--default-signal=INT");

    private Process build;

    @AfterEach
    void killBuild() {
        if (build != null) build.destroyForcibly();
    }

    @Test
    void firstCtrlCDuringABuildAsksForASecond() throws Exception {
        var command = new ArrayList<String>();
        if (sigintIgnored()) {
            assumeTrue(canResetSigint(), "SIGINT is ignored here and env cannot reset it for the stand-in (#1500)");
            command.addAll(RESET_SIGINT);
        }
        command.addAll(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Build.class.getName()));
        build = new ProcessBuilder(command).start();
        var out = new BufferedReader(new InputStreamReader(build.getInputStream(), StandardCharsets.UTF_8));
        var err = new BufferedReader(new InputStreamReader(build.getErrorStream(), StandardCharsets.UTF_8));
        assertEquals("building", out.readLine());

        interrupt();
        String warning;
        do warning = err.readLine(); // past anything the JVM itself prints there
        while (warning != null && !warning.contains("Press Ctrl+C again"));
        assertTrue(warning != null, "no warning after the first Ctrl+C");
        assertEquals("", out.readLine()); // the blank line that ends a warning
        assertFalse(build.waitFor(500, TimeUnit.MILLISECONDS), "one Ctrl+C ended the build");

        interrupt();
        assertTrue(build.waitFor(20, TimeUnit.SECONDS), "a second Ctrl+C did not end the build");
        assertEquals(130, build.exitValue());
        assertEquals("Build interrupted", out.readLine());
    }

    /**
     * Whether this JVM inherited SIGINT ignored, as a suite started detached does (#1500). The
     * stand-in would inherit that too and get no SIGINT handler, so it is reset to the default.
     */
    private static boolean sigintIgnored() throws IOException {
        var status = Path.of("/proc/self/status");
        if (!Files.exists(status)) return false;
        try (var lines = Files.lines(status)) {
            var prefix = "SigIgn:";
            return lines.filter(line -> line.startsWith(prefix))
                    .anyMatch(line -> (Long.parseUnsignedLong(line.substring(prefix.length()).strip(), 16)
                            & 1L << 1) != 0); // signal n is bit n-1, SIGINT is 2
        }
    }

    /** Whether this {@code env} has {@code --default-signal} (GNU coreutils 8.31 or later). */
    private static boolean canResetSigint() throws InterruptedException {
        var probe = new ArrayList<>(RESET_SIGINT);
        probe.add("true");
        try {
            return new ProcessBuilder(probe).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void interrupt() throws Exception {
        var kill = new ProcessBuilder("kill", "-INT", String.valueOf(build.pid())).inheritIO().start();
        assertEquals(0, kill.waitFor());
    }

    @Test
    void secondCtrlCCountsOnlyWithinTheWindow() {
        var now = new AtomicLong();
        var confirmation = new BuildInterrupt(() -> true, now::get);
        assertFalse(confirmation.shouldInterrupt());
        now.addAndGet(BuildInterrupt.WINDOW.toNanos() + 1);
        assertFalse(confirmation.shouldInterrupt(), "a Ctrl+C long after the first ended the build");
        now.addAndGet(BuildInterrupt.WINDOW.toNanos());
        assertTrue(confirmation.shouldInterrupt());
    }

    @Test
    void ctrlCOutsideABuildInterruptsAtOnce() {
        var confirmation = new BuildInterrupt(() -> false, () -> 0);
        assertTrue(confirmation.shouldInterrupt());
    }

    /** A build stand-in: guarded as {@code isx build} is, with its shutdown hook. */
    public static final class Build {
        public static void main(String[] args) throws Exception {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("Build interrupted")));
            try (var ignored = BuildInterrupt.install(() -> true)) {
                System.out.println("building");
                Thread.sleep(60_000);
            }
        }
    }
}
