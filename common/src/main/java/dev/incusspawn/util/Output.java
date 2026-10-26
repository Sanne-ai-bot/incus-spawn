package dev.incusspawn.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where isx's messages go: the one seam between the code that reports and whoever owns the
 * terminal (#1703). Code reports through {@link BuildOutput}'s static methods, which hand each
 * message to the bound output ({@link BuildOutput#bind}); the owner of the terminal binds once.
 * The CLI keeps the default, a {@link TerminalOutput}. The TUI binds {@link #diagnosticsTo} its
 * warning log while it draws and {@link #copying} it from a terminal it has released; {@code isx
 * mcp} binds a terminal on stderr alone, and the proxy its own log.
 *
 * <p>The vocabulary is what isx already prints. Progress (headers, groups, steps, live steps,
 * lists) and notes mean something only on a terminal, so an output that is not one ignores them by
 * default. Diagnostics never are: {@link #stepWarn}, {@link #warnBanner} and {@link #stepFail}
 * default to {@link #warn}, so an output that implements only that one method still receives
 * every warning and error.
 */
public interface Output {

    /** A warning, with any lines of advice under it. Blank messages are skipped by every output. */
    void warn(String msg, String... details);

    /**
     * A warning raised beside a command's flow rather than in it, by code with no caller to hand
     * it a sink ({@code Warnings}): a terminal prints it on stderr alone, so a query command's
     * stdout still holds only its results.
     */
    default void warnAside(String msg) {
        warn(msg);
    }

    /** A warning about the step above. */
    default void stepWarn(String msg) {
        warn(msg);
    }

    /** A warning that must stand out: a title and its lines. */
    default void warnBanner(String title, String... lines) {
        warn(title, lines);
    }

    /** Fail the live step, then report {@code msg}. */
    default void stepFail(String msg) {
        warn(msg);
    }

    /** An informational line beside the warnings; not a warning, so not kept off a terminal. */
    default void note(String msg) {}

    default void header(String msg, String detail) {}

    default void buildHeader(String name, int index, int total) {}

    default void branchHeader(String name, String source) {}

    default void section(String msg) {}

    /** Open a titled group of steps, indented under it until the returned group is closed. */
    default Group group(String title, String detail) {
        return () -> {};
    }

    /** The indent of a step line at the current nesting; empty off a terminal. */
    default String indent() {
        return "";
    }

    default void step(String msg) {}

    default void stepNote(String msg) {}

    default void ok(String msg) {}

    default void success(String msg) {}

    default void list(Collection<String> items) {}

    default void stepStart(String msg) {}

    default void stepProgress(String detail) {}

    /** Complete the live step, with a result shown beside it when {@code detail} is not null. */
    default void stepDone(String detail) {}

    default void stepBreak() {}

    default void releaseTerminal() {}

    default void abandonStep() {}

    /**
     * The outputs that remember what {@code Warnings} reported through this one: itself, unless
     * it only passes messages on, so a warning the TUI's log already holds is not added again by a
     * build that copies its warnings there.
     */
    default List<Output> destinations() {
        return List.of(this);
    }

    /** A {@link #group} being printed; closing it returns to the enclosing indent. */
    @FunctionalInterface
    interface Group extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * An output that is not a terminal: each diagnostic reaches {@code sink} as one message, its
     * lines made safe ({@link OutputFormat#oneLine}) since they can quote template text, and
     * everything else is dropped. For the TUI while it draws, a pre-TUI hold, a daemon's log.
     */
    static Output diagnosticsTo(Consumer<String> sink) {
        return (msg, details) -> {
            var text = message(msg, details);
            if (text != null) sink.accept(text);
        };
    }

    /**
     * Everything to {@code terminal}, and every warning also to {@code log}: for a terminal the
     * TUI has released to a build or a branch, which it will then draw over. A failed step is not
     * copied: whoever called it reports the failure, as the TUI's status line does.
     */
    static Output copying(Output terminal, Output log) {
        return new CopyingOutput(terminal, log, true);
    }

    /**
     * Everything to {@code terminal}, but what {@code Warnings} reports to {@code log} alone: for
     * the window before the TUI opens, whose notices are held for its log, while a warning about
     * a step still prints under it.
     */
    static Output holding(Output terminal, Output log) {
        return new CopyingOutput(terminal, log, false);
    }

    /** {@code msg} and its {@code details} as one message, each line made safe; null when {@code msg} is blank. */
    private static String message(String msg, String... details) {
        var text = BuildOutput.warningText(msg);
        if (text == null) return null;
        return Stream.concat(Stream.of(text), Arrays.stream(details))
                .map(BuildOutput::safeLines)
                .collect(Collectors.joining("\n"));
    }
}
