package dev.incusspawn.util;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * An {@link Output} for a daemon's log ({@link Output#logTo}): a progress line becomes an info
 * line, a note a note line, a diagnostic a warning line ({@link Output#message}, as every output
 * that is not a terminal makes it), each line of a message its own. A live step's redraws have
 * nothing a log keeps, so only its start is a line.
 */
final class LogOutput implements Output {

    private final Consumer<String> info;
    private final Consumer<String> notes;
    private final Consumer<String> warn;

    LogOutput(Consumer<String> info, Consumer<String> notes, Consumer<String> warn) {
        this.info = info;
        this.notes = notes;
        this.warn = warn;
    }

    private static void lines(Consumer<String> sink, String msg) {
        if (msg == null || msg.isBlank()) return;
        BuildOutput.safeLines(msg.strip()).lines().forEach(sink);
    }

    @Override public void warn(String msg, String... details) {
        var text = Output.message(msg, details);
        if (text != null) text.lines().forEach(warn);
    }

    @Override public void note(String msg) { lines(notes, msg); }
    @Override public void header(String msg, String detail) { lines(info, detail == null ? msg : msg + " " + detail); }
    @Override public void buildHeader(String name, int index, int total) { lines(info, "Building " + name); }
    @Override public void branchHeader(String name, String source) { lines(info, name + " <- " + source); }
    @Override public void section(String msg) { lines(info, msg); }
    @Override public void step(String msg) { lines(info, msg); }
    @Override public void stepNote(String msg) { lines(info, msg); }
    @Override public void ok(String msg) { lines(info, msg); }
    @Override public void success(String msg) { lines(info, msg); }
    @Override public void list(Collection<String> items) { lines(info, String.join(", ", items)); }
    @Override public void stepStart(String msg) { lines(info, msg); }
}
