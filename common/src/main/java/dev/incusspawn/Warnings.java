package dev.incusspawn;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warnings from code that has no caller to hand it a sink.
 * <p>
 * Taking a sink from the caller stays the first choice (see CLAUDE.md). This is for the rest:
 * code several layers below any command, such as the {@code ToolDefLoader} that
 * {@code ToolProxyResolver} builds for account validation, where threading a sink through every
 * caller would mean changing all of them. Such code calls {@link #warn}, which goes to the bound
 * {@link Output} like every other warning ({@link BuildOutput#bind}): stderr by default, the
 * TUI's warning log while the TUI draws.
 * <p>
 * Each output reports a distinct message once. The same definition is often loaded many times in
 * one command (every fresh loader re-reads it), and printing the same line each time helps nobody.
 * The memory belongs to the output, not the process: a build the TUI runs on the released
 * terminal must print a broken file's warning even though the TUI already logged it.
 */
public final class Warnings {

    /** What each output has reported; weak, so an output no longer bound takes its memory along. */
    private static final Map<Output, Set<String>> REPORTED = Collections.synchronizedMap(new WeakHashMap<>());

    private Warnings() {}

    /** Report a warning, unless the bound output already reported the same message. */
    public static void warn(String message) {
        warn(BuildOutput.current(), message);
    }

    /**
     * Report a warning to {@code output}, unless it already reported the same message: to each of
     * its {@link Output#destinations()} that has not.
     */
    public static void warn(Output output, String message) {
        if (message == null || message.isBlank()) return;
        for (var destination : output.destinations()) {
            if (reported(destination).add(message)) {
                destination.warnAside(message);
            }
        }
    }

    /**
     * Let the bound output report every message again: for a long-running process that re-reads
     * definitions, as the proxy does on each config reload, or an explicit reload in the TUI, so a
     * problem still there is reported again rather than once per process.
     */
    public static void forgetReported() {
        forgetReported(BuildOutput.current());
    }

    /** Let {@code output} report every message again. */
    public static void forgetReported(Output output) {
        output.destinations().forEach(destination -> reported(destination).clear());
    }

    private static Set<String> reported(Output output) {
        return REPORTED.computeIfAbsent(output, o -> ConcurrentHashMap.newKeySet());
    }
}
