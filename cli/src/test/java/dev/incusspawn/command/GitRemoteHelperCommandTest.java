package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitRemoteHelperCommandTest {

    @Test
    void repoHintsShowDefinitionTextAsText() {
        // A template, project-local included, decides a repository's path and URL (#1624).
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/app\033]0;pwned\007.git");
        repo.setPath("~/\033[2Japp");

        assertEquals(List.of(
                "The path 'x 2J' is not a git repository in instance 'work'.",
                "Known repositories:",
                "  ~/ [2Japp  (https://github.com/owner/app ]0;pwned .git)"),
                GitRemoteHelperCommand.repoHints("work", "x\u009b2J", List.of(repo)));
    }

    @Test
    void theGuestsStderrIsShownAsText() throws Exception {
        // Whatever runs in the instance decides what git's service prints there (#1588's class).
        var shown = new java.io.ByteArrayOutputStream();
        var stderr = new GuestStderr(
                new java.io.PrintStream(shown, true, java.nio.charset.StandardCharsets.UTF_8),
                GitRemoteHelperCommand::saysNotARepository);
        var bytes = "fatal: not a git repository: '/home/agentuser/caf\u00e9\033]0;pwned\007'\r\ntail"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // One byte at a time, as git's service may write, so a character's bytes arrive apart.
        for (var b : bytes) stderr.write(b);
        stderr.close();

        assertEquals("fatal: not a git repository: '/home/agentuser/caf\u00e9'\ntail\n",
                shown.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(stderr.sawWatched());
    }

    @Test
    void aLongLineIsCutBetweenCharacters() throws Exception {
        var shown = new java.io.ByteArrayOutputStream();
        var stderr = new GuestStderr(
                new java.io.PrintStream(shown, true, java.nio.charset.StandardCharsets.UTF_8));
        // 'é' is two bytes, so a cut at every 64 KiB would fall inside one.
        var line = "a" + "\u00e9".repeat(70_000);
        var bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        stderr.write(bytes, 0, bytes.length);
        stderr.close();

        var text = shown.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(text.contains("\ufffd"), "no character is split");
        assertEquals(line, text.replace("\n", ""));
    }

    @Test
    void aLineOfContinuationBytesIsStillCut() {
        // The guest decides these bytes: without a cut, the host's isx would buffer them forever.
        var shown = new java.io.ByteArrayOutputStream();
        var stderr = new GuestStderr(new java.io.PrintStream(shown, true));
        var bytes = new byte[200_000];
        java.util.Arrays.fill(bytes, (byte) 0x80);
        stderr.write(bytes, 0, bytes.length);
        assertTrue(shown.size() > 0, "a line past the limit is shown before its end");
    }

    @Test
    void gitsServicesSayAPathIsNotARepositoryInTheirOwnWords() {
        // git upload-pack and receive-pack on a path that is not a repository (git 2.x).
        var stderr = new GuestStderr(
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                GitRemoteHelperCommand::saysNotARepository);
        var bytes = "fatal: '/home/agentuser/x' does not appear to be a git repository\rlater frame\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        stderr.write(bytes, 0, bytes.length);
        assertTrue(stderr.sawWatched());
    }
}
