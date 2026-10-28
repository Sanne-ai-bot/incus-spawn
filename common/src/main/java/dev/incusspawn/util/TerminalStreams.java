package dev.incusspawn.util;

import java.io.Console;
import java.io.PrintStream;

/**
 * The process's own streams and console, for the code that hands the terminal over or draws on
 * it whole (#1703): an interactive guest command's inherited output ({@code IncusClient}), an
 * interactive shell's raw bytes ({@code IncusApi}), the live renderer ({@code TerminalProgress}),
 * and a question asked where {@code Prompts} cannot reach ({@code HostRepoRefresh}); and the one
 * check of whether a person is at a terminal ({@code Prompts}). A gate of
 * {@code RawOutputGateTest}, which also lets only those files call it: anything else reports
 * through {@link BuildOutput} or a caller's {@link Output}.
 */
public final class TerminalStreams {

    private TerminalStreams() {}

    /** The process's stdout, read when called: a step's guard or a test may have replaced it. */
    public static PrintStream out() {
        return System.out;
    }

    /** The process's stderr, read when called. */
    public static PrintStream err() {
        return System.err;
    }

    /** Whether the JDK reports a console at all, as colour and animation ask. */
    public static boolean hasConsole() {
        return System.console() != null;
    }

    /**
     * The console, or null unless stdin and stdout are a terminal ({@link Console#isTerminal}). On
     * JDK 25 {@code System.console()} is already null when either is redirected; this guards
     * against a console that exists anyway (JDK 22 to 24, or {@code -Djdk.console=jdk.internal.le}).
     */
    public static Console terminal() {
        var console = System.console();
        return console != null && console.isTerminal() ? console : null;
    }
}
