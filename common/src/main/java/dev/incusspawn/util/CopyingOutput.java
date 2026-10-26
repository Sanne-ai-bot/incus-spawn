package dev.incusspawn.util;

import java.util.List;

/**
 * A terminal that prints everything, and whose warnings also go to a log: all of them
 * ({@link Output#copying}), or only those raised through {@code Warnings} ({@link Output#holding}),
 * which reach the log instead of the terminal. A failed step stays on the terminal, where it ends
 * the live step and whose caller reports the failure itself; progress and notes stay there too.
 */
final class CopyingOutput extends ForwardingOutput {

    private final Output terminal;
    private final Output log;
    /** Whether every warning is copied to the log, not only those raised through {@code Warnings}. */
    private final boolean copyAll;

    CopyingOutput(Output terminal, Output log, boolean copyAll) {
        this.terminal = terminal;
        this.log = log;
        this.copyAll = copyAll;
    }

    @Override
    Output delegate() {
        return terminal;
    }

    @Override
    public List<Output> destinations() {
        return copyAll ? List.of(log, terminal) : List.of(log);
    }

    @Override
    public void warn(String msg, String... details) {
        if (copyAll) log.warn(msg, details);
        terminal.warn(msg, details);
    }

    @Override
    public void error(String msg, String... details) {
        if (copyAll) log.error(msg, details);
        terminal.error(msg, details);
    }

    @Override
    public void warnAside(String msg) {
        // What Warnings reports: always kept in the log, and printed only when copying, not holding.
        log.warnAside(msg);
        if (copyAll) terminal.warnAside(msg);
    }

    @Override
    public void stepWarn(String msg) {
        if (copyAll) log.stepWarn(msg);
        terminal.stepWarn(msg);
    }

    @Override
    public void warnBanner(String title, String... lines) {
        if (copyAll) log.warnBanner(title, lines);
        terminal.warnBanner(title, lines);
    }
}
