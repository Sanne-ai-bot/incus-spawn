package dev.incusspawn.proxy;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ProxyLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static volatile Boolean debugOverride;
    private static volatile boolean stderrSuppressed;

    private ProxyLog() {}

    /**
     * Suppress stderr output while the TUI owns the terminal.  File logging continues
     * unconditionally so the lifecycle log stays complete.
     *
     * @return the previous setting, for a caller that puts it back
     */
    public static boolean setSuppressStderr(boolean suppress) {
        var previous = stderrSuppressed;
        stderrSuppressed = suppress;
        return previous;
    }

    public static boolean isDebugEnabled() {
        var v = debugOverride;
        return v != null ? v : Boolean.getBoolean("isx.proxy.debug");
    }

    public static void setDebugEnabled(boolean enabled) {
        debugOverride = enabled;
    }

    public static void debug(String message) {
        if (!isDebugEnabled()) return;
        logToFile("DEBUG", message);
    }

    /**
     * A line for stderr alone, never the lifecycle log: what a person watching the proxy may want,
     * such as a request's debug line, which a lifecycle log of starts and reloads does not keep.
     */
    public static void trace(String message) {
        if (stderrSuppressed) return;
        // No level of its own: such a line names its kind, as "[debug] ..." does
        System.err.println(LocalDateTime.now().format(FMT) + " " + message);
    }

    public static void info(String message) {
        log("INFO", message);
    }

    public static void warn(String message) {
        log("WARN", message);
    }

    public static void error(String message) {
        log("ERROR", message);
    }

    private static void log(String level, String message) {
        var line = LocalDateTime.now().format(FMT) + " [" + level + "] " + message;
        if (!stderrSuppressed) System.err.println(line);
        writeToFile(line);
    }

    private static void logToFile(String level, String message) {
        var line = LocalDateTime.now().format(FMT) + " [" + level + "] " + message;
        writeToFile(line);
    }

    private static void writeToFile(String line) {
        try {
            var path = Environment.proxyLifecycleLogFile();
            Files.createDirectories(path.getParent());
            Files.writeString(path, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
