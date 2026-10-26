package dev.incusspawn.util;

import java.util.Collection;
import java.util.List;

/**
 * A terminal that prints everything, and whose warnings also go to a log: all of them
 * ({@link Output#copying}), or only those raised through {@code Warnings} ({@link Output#holding}),
 * which reach the log instead of the terminal. A failed step stays on the terminal, where it ends
 * the live step and whose caller reports the failure itself.
 */
final class CopyingOutput implements Output {

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
    public List<Output> destinations() {
        return copyAll ? List.of(log, terminal) : List.of(log);
    }

    @Override
    public void warn(String msg, String... details) {
        if (copyAll) log.warn(msg, details);
        terminal.warn(msg, details);
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

    @Override public void stepFail(String msg) { terminal.stepFail(msg); }

    @Override public void note(String msg) { terminal.note(msg); }
    @Override public void header(String msg, String detail) { terminal.header(msg, detail); }
    @Override public void buildHeader(String name, int index, int total) { terminal.buildHeader(name, index, total); }
    @Override public void branchHeader(String name, String source) { terminal.branchHeader(name, source); }
    @Override public void section(String msg) { terminal.section(msg); }
    @Override public Group group(String title, String detail) { return terminal.group(title, detail); }
    @Override public String indent() { return terminal.indent(); }
    @Override public void step(String msg) { terminal.step(msg); }
    @Override public void stepNote(String msg) { terminal.stepNote(msg); }
    @Override public void ok(String msg) { terminal.ok(msg); }
    @Override public void success(String msg) { terminal.success(msg); }
    @Override public void list(Collection<String> items) { terminal.list(items); }
    @Override public void stepStart(String msg) { terminal.stepStart(msg); }
    @Override public void stepProgress(String detail) { terminal.stepProgress(detail); }
    @Override public void stepDone(String detail) { terminal.stepDone(detail); }
    @Override public void stepBreak() { terminal.stepBreak(); }
    @Override public void releaseTerminal() { terminal.releaseTerminal(); }
    @Override public void abandonStep() { terminal.abandonStep(); }
}
