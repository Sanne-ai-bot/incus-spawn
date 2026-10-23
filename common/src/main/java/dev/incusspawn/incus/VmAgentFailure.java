package dev.incusspawn.incus;

import dev.incusspawn.Environment;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * Recognizes, in a VM's console log, the lines that say its incus-agent failed to start.
 *
 * <p>A VM whose agent never comes up is otherwise indistinguishable from one still booting: every
 * exec simply fails. The console log says which it is, in lines short and distinctive enough to
 * match (#844). The SELinux case of #842 logs both of these:
 *
 * <pre>
 * avc:  denied  { listen } for  pid=1288 comm="incus-agent" ... tclass=vsock_socket permissive=0
 * systemd[1]: Failed to start incus-agent.service - Incus - agent.
 * </pre>
 */
public final class VmAgentFailure {

    private VmAgentFailure() {}

    /** At most this many matched lines are reported; the agent's restarts repeat them. */
    static final int MAX_LINES = 6;

    /**
     * The lines of {@code consoleLog} that report the agent failing, trimmed and deduplicated, as
     * text that cannot drive a terminal: whatever runs in the guest can write its console (#1588).
     */
    public static List<String> matchingLines(String consoleLog) {
        if (consoleLog == null || consoleLog.isEmpty()) return List.of();
        return consoleLog.lines()
                .filter(l -> l.contains("incus-agent"))
                .map(l -> OutputFormat.oneLine(BuildOutput.guestText(l)).strip())
                .filter(VmAgentFailure::isFailure)
                .distinct()
                .limit(MAX_LINES)
                .toList();
    }

    /**
     * {@link #matchingLines}, or none when systemd started the agent after its last failure:
     * a failure the agent recovered from says nothing about why it is unresponsive now.
     * systemd reports both on the console only during boot, so their order is comparable.
     */
    public static List<String> unrecoveredLines(String consoleLog) {
        if (consoleLog == null || consoleLog.isEmpty()) return List.of();
        int lastFailure = -1;
        int lastStart = -1;
        var lines = consoleLog.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (!line.contains("incus-agent")) continue;
            if (line.contains("Started incus-agent")) lastStart = i;
            else if (isFailure(line)) lastFailure = i;
        }
        return lastFailure > lastStart ? matchingLines(consoleLog) : List.of();
    }

    /**
     * Say that {@code name}'s agent failed to start, quoting {@code lines} from its console log,
     * and where the whole log was saved ({@link #save}; null for nowhere).
     */
    public static String report(String name, List<String> lines, Path savedLog) {
        var msg = new StringBuilder("The incus-agent in VM ").append(name)
                .append(" failed to start. Its console log says:");
        lines.forEach(l -> msg.append("\n  ").append(l));
        return msg.append(pointer("Full boot log: ", savedLog)).toString();
    }

    /**
     * Save VM {@code name}'s console log {@code log} on the host ({@link Environment#consoleLogFile})
     * for a message to point at, and return where; null when there is nothing to save or it cannot
     * be written. The log comes over the API, so this works on macOS, whose host has no
     * {@code incus console} (#1139).
     */
    public static Path save(String name, String log) {
        if (log.isEmpty()) return null;
        try {
            var file = Environment.consoleLogFile(name);
            // What the guest printed is the user's alone, as it is through the Incus API: the
            // directory and the file are created with that mode, never narrowed afterwards
            var posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            if (posix) {
                // Only console-logs is private; the state directories above it are made as every
                // other isx path makes them
                var dir = file.getParent();
                Files.createDirectories(dir.getParent());
                if (!Files.isDirectory(dir)) Files.createDirectory(dir,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Files.deleteIfExists(file);
                Files.createFile(file,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, log);
            return file;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** A message line naming the saved console log after {@code lead}, or "" when none was saved. */
    public static String pointer(String lead, Path savedLog) {
        return savedLog == null ? "" : "\n" + lead + savedLog;
    }

    static boolean isFailure(String line) {
        if (line.contains("Failed to start incus-agent")) return true;
        return line.contains("avc:") && line.contains("denied") && line.contains("comm=\"incus-agent\"");
    }
}
