package dev.incusspawn.tui;

import dev.incusspawn.util.BuildOutput;

import java.io.PrintStream;

/**
 * Notes every write to the process's stdout and stderr while it is open: how {@link PreTuiOutput}
 * knows that something was printed before the TUI opens, whoever printed it, so the hand-over
 * waits for it to be read (#1154). The one place the TUI replaces the raw streams, a gate class
 * of {@code RawOutputGateTest} as {@code StdoutOnStderr} is: what is written still reaches the
 * terminal as it is written.
 */
final class StreamWatch implements AutoCloseable {

    private final PrintStream out;
    private final PrintStream err;
    private final PrintStream notingOut;
    private final PrintStream notingErr;

    /** Run {@code onWrite} before each write to stdout or stderr, until {@link #close}. */
    StreamWatch(Runnable onWrite) {
        out = System.out;
        err = System.err;
        notingOut = BuildOutput.onWrite(out, onWrite);
        notingErr = BuildOutput.onWrite(err, onWrite);
        System.setOut(notingOut);
        System.setErr(notingErr);
    }

    /** Put the streams back: only our own, since whatever replaced them meanwhile restores its own. */
    @Override
    public void close() {
        notingOut.flush();
        notingErr.flush();
        if (System.out == notingOut) System.setOut(out);
        if (System.err == notingErr) System.setErr(err);
    }
}
