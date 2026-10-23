package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the Claude Code action's command with a stub {@code claude} that reports how it was started.
 * Linux only: the command runs in a Fedora guest and uses GNU {@code find -printf}, which macOS lacks.
 */
@EnabledOnOs(OS.LINUX)
class ClaudeActionTest {

    @TempDir
    Path home;

    @Test
    void withoutHistoryClaudeStartsFresh() throws Exception {
        assertEquals("claude [] in " + home, runAction());
    }

    @Test
    void continuesTheNewestSessionInItsDirectory() throws Exception {
        var older = Files.createDirectories(home.resolve("older"));
        var newer = Files.createDirectories(home.resolve("newer"));
        session("older", older, 1_000);
        session("newer", newer, 2_000);

        assertEquals("claude [--continue] in " + newer, runAction());
    }

    /**
     * The newest session's directory was removed (a deleted checkout or worktree). {@code claude
     * --continue} from the home directory then finds no conversation and exits, and the action
     * returned to a shell instead of starting Claude Code.
     */
    @Test
    void startsFreshWhenTheNewestSessionsDirectoryIsGone() throws Exception {
        session("gone", home.resolve("gone"), 2_000);

        assertEquals("claude [] in " + home, runAction());
    }

    /** A transcript that names no directory is continued from the home directory, as before. */
    @Test
    void continuesFromHomeWhenTheNewestSessionNamesNoDirectory() throws Exception {
        var project = Files.createDirectories(home.resolve(".claude/projects/nocwd"));
        Files.writeString(project.resolve("nocwd.jsonl"), "{\"type\":\"file-history-snapshot\"}\n");

        assertEquals("claude [--continue] in " + home, runAction());
    }

    private void session(String id, Path cwd, long mtimeSeconds) throws IOException {
        var project = Files.createDirectories(home.resolve(".claude/projects/" + id));
        var file = project.resolve(id + ".jsonl");
        Files.writeString(file, "{\"type\":\"file-history-snapshot\"}\n"
                + "{\"type\":\"user\",\"cwd\":\"" + cwd + "\",\"sessionId\":\"" + id + "\"}\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(mtimeSeconds)));
    }

    private String runAction() throws Exception {
        var bin = Files.createDirectories(home.resolve("bin"));
        var claude = bin.resolve("claude");
        Files.writeString(claude, "#!/bin/sh\necho \"claude [$*] in $PWD\"\n");
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwx------"));
        var command = new ClaudeSetup().actions().getFirst().getCommand();
        var pb = new ProcessBuilder("bash", "-c", command).directory(home.toFile()).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        var process = pb.start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        process.waitFor();
        return out;
    }
}
