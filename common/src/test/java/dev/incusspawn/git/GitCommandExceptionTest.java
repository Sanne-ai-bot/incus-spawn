package dev.incusspawn.git;

import dev.incusspawn.config.GitTransferConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** isx's words kept apart from Git's in a failure (#1343, Sanne's decisions on #1403). */
class GitCommandExceptionTest {

    private static final String PUBLICKEY = "git@github.com: Permission denied (publickey).\n"
            + "fatal: Could not read from remote repository.";

    @Test
    void gitsOutputIsQuotedUnderAHeaderAfterIsxsNote() {
        assertEquals(List.of("isx says this.", "Git's output:", "  | fatal: one", "  | error: two"),
                GitCommandException.details("isx says this.", "fatal: one\n\nerror: two"));
        assertEquals(List.of(), GitCommandException.details(null, " "));
    }

    @Test
    void forwardedOutputLosesUrlCredentials() {
        var details = String.join("\n", GitCommandException.details(null,
                "fatal: unable to access 'https://user:s3cr3t-token@example.com/r.git/': 403\n"
                        + "fatal: repository 'https://glpat-t0ken-only@gitlab.example.com/r.git/' not found"));
        assertFalse(details.contains("s3cr3t-token") || details.contains("glpat-t0ken-only"), details);
        assertTrue(details.contains("gitlab.example.com/r.git"), details);
    }

    @Test
    void aBuildsSshFailureSaysWhatIsxDidAndOnlyWhatItKnows() {
        var note = GitCommandException.promptNote(PUBLICKEY,
                new PreparationGit.SshSetup("isx added -o BatchMode=yes to your global core.sshCommand", true,
                        "a global core.sshCommand (a repository's own does not apply to builds)"));
        assertTrue(note.startsWith("isx added -o BatchMode=yes to your global core.sshCommand, so ssh could not"), note);
        assertTrue(note.endsWith("choose another with a global core.sshCommand (a repository's own does not apply to builds)."), note);
        // A refused key may have no passphrase at all: isx cannot tell which.
        assertTrue(note.contains("If your key has one, load it into ssh-agent; otherwise check that the server accepts"), note);
        assertFalse(note.contains("is kept"), note);
    }

    @Test
    void anSshFailureUnderTheUsersOwnSshSetupAddsNoIsxNote() {
        // isx changed nothing there: ssh's own words are the explanation.
        assertNull(GitCommandException.promptNote(PUBLICKEY, null));
        assertNull(GitCommandException.promptNote("Host key verification failed.", null));
    }

    @Test
    void aHostKeyFailureDoesNotJustSayTrustTheNewKey() {
        var note = GitCommandException.promptNote("Host key verification failed.\n"
                + "fatal: Could not read from remote repository.", new PreparationGit.SshSetup("isx ran ssh with -o BatchMode=yes", false, "core.sshCommand"));
        assertTrue(note.startsWith("isx ran ssh with -o BatchMode=yes, so ssh could not ask about the host's key"), note);
        assertTrue(note.contains("check its fingerprint") && note.contains("if its key changed, find out why"), note);
    }

    @Test
    void aCredentialPromptFailureNamesTheSetting() {
        var note = GitCommandException.promptNote(
                "fatal: could not read Username for 'https://github.com': terminal prompts disabled", null);
        assertTrue(note.contains("GIT_TERMINAL_PROMPT=0") && note.contains("credential helper"), note);
        assertNull(GitCommandException.promptNote("fatal: repository not found", new PreparationGit.SshSetup("x", true, "y")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"env SSH_AUTH_SOCK=/x ssh -i k", "ssh\t-i k", "'/usr/bin/ssh' -i k", "sshpass -p x ssh", "plink -batch"})
    void aBuildSetsNoPromptForWhateverRunsSsh(String sshCommand, @TempDir Path repo) throws Exception {
        GitTestUtils.git(repo, "init", "-q");
        GitTestUtils.git(repo, "config", "core.sshCommand", sshCommand);
        var environment = noPrompt(new HashMap<>(), repo);
        var expected = PreparationGit.withBatchMode(sshCommand);
        if (expected != null) {
            assertEquals(expected, environment.get("GIT_SSH_COMMAND"));
            assertEquals("isx added -o BatchMode=yes to your global core.sshCommand", lastSetup.done());
        } else {
            assertFalse(environment.containsKey("GIT_SSH_COMMAND"), "a wrapper runs as it is");
            assertEquals("isx set SSH_ASKPASS_REQUIRE=force with SSH_ASKPASS=false for your global core.sshCommand",
                    lastSetup.done());
        }
    }

    @Test
    void aBuildSaysWhichSshCommandItChanged(@TempDir Path tmp) {
        var both = noPrompt(new HashMap<>(Map.of("GIT_SSH", "/usr/bin/ssh", "GIT_SSH_COMMAND", "ssh -i k")), null);
        // Git runs GIT_SSH_COMMAND first, so that is the one that needs the option.
        assertEquals("ssh -o BatchMode=yes -i k", both.get("GIT_SSH_COMMAND"));
        assertEquals("isx added -o BatchMode=yes to your GIT_SSH_COMMAND", lastSetup.done());
        var gitSsh = noPrompt(new HashMap<>(Map.of("GIT_SSH", "/usr/bin/ssh")), null);
        assertFalse(gitSsh.containsKey("GIT_SSH_COMMAND"));
        assertEquals("isx set SSH_ASKPASS_REQUIRE=force with SSH_ASKPASS=false for your GIT_SSH program", lastSetup.done());
        // git config fails in a directory that does not exist: isx cannot tell what the user chose.
        var unreadable = noPrompt(new HashMap<>(), tmp.resolve("missing"));
        assertFalse(unreadable.containsKey("GIT_SSH_COMMAND"), "an unknown choice is kept");
        assertEquals("isx set SSH_ASKPASS_REQUIRE=force with SSH_ASKPASS=false for ssh", lastSetup.done());
        assertEquals("ssh -o BatchMode=yes", noPrompt(new HashMap<>(), null).get("GIT_SSH_COMMAND"));
        assertEquals("isx ran ssh with -o BatchMode=yes", lastSetup.done());
    }

    @Test
    void aBuildFollowsGitsOrderCoreSshCommandBeforeGitSsh(@TempDir Path repo) throws Exception {
        GitTestUtils.git(repo, "init", "-q");
        GitTestUtils.git(repo, "config", "core.sshCommand", "ssh -i k");
        // Git runs core.sshCommand over GIT_SSH: the option goes there, and the note names it.
        var environment = noPrompt(new HashMap<>(Map.of("GIT_SSH", "/usr/bin/ssh")), repo);
        assertEquals("ssh -o BatchMode=yes -i k", environment.get("GIT_SSH_COMMAND"));
        assertEquals("isx added -o BatchMode=yes to your global core.sshCommand", lastSetup.done());
        var note = GitCommandException.promptNote(PUBLICKEY, lastSetup);
        assertTrue(note.endsWith("choose another with a global core.sshCommand (a repository's own does not apply to builds)."), note);
    }

    @Test
    void theKeyAdviceNamesTheSettingGitActuallyUsed() {
        noPrompt(new HashMap<>(Map.of("GIT_SSH_COMMAND", "ssh -i k")), null);
        assertTrue(GitCommandException.promptNote(PUBLICKEY, lastSetup).endsWith("choose another with your GIT_SSH_COMMAND."));
        noPrompt(new HashMap<>(Map.of("GIT_SSH", "/usr/bin/ssh")), null);
        assertTrue(GitCommandException.promptNote(PUBLICKEY, lastSetup).endsWith("choose another with your GIT_SSH program."));
    }

    @Test
    void sshsCrlfErrorLinesReachTheNote(@TempDir Path tmp) throws Exception {
        // Like ssh, which ends every message with \r\n: a whole line, not a progress update.
        var fakeSsh = java.nio.file.Files.writeString(tmp.resolve("fake-ssh"),
                "#!/bin/sh\nprintf 'Host key verification failed.\\r\\n' >&2\nexit 255\n");
        java.nio.file.Files.setPosixFilePermissions(fakeSsh, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var builder = new ProcessBuilder("git", "ls-remote", "ssh://example.invalid/r.git").redirectErrorStream(true);
        builder.environment().put("GIT_SSH_COMMAND", fakeSsh.toString());
        var result = GitProcess.run(builder, null, true, GitTransferConfig.DEFAULT, p -> {});
        assertEquals(GitProcess.Status.FAILED, result.status());
        assertTrue(result.diagnostics().lines().anyMatch(l -> l.equals("Host key verification failed.")), result.diagnostics());
        var note = GitCommandException.promptNote(result.diagnostics(), result.ssh());
        assertNotNull(note, result.diagnostics());
        assertTrue(note.startsWith("isx set SSH_ASKPASS_REQUIRE=force with SSH_ASKPASS=false for your GIT_SSH_COMMAND"), note);
    }

    @Test
    void realSshErrorLinesAreKept() throws Exception {
        int closed;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        var result = GitProcess.run(new ProcessBuilder("git", "ls-remote", "ssh://127.0.0.1:" + closed + "/r.git")
                .redirectErrorStream(true), null, true, GitTransferConfig.DEFAULT, p -> {});
        assertEquals(GitProcess.Status.FAILED, result.status());
        assertTrue(result.diagnostics().lines().anyMatch(l -> l.startsWith("ssh: connect to host 127.0.0.1 port " + closed)),
                result.diagnostics());
    }

    private PreparationGit.SshSetup lastSetup;

    private Map<String, String> noPrompt(Map<String, String> environment, Path repo) {
        environment.put("PATH", System.getenv("PATH"));
        lastSetup = PreparationGit.nonInteractiveSsh(environment, repo, true);
        assertTrue(lastSetup.build());
        // OpenSSH 8.4+ asks this program instead of the terminal, whichever command started it.
        assertEquals("force", environment.get("SSH_ASKPASS_REQUIRE"));
        assertEquals("false", environment.get("SSH_ASKPASS"));
        assertEquals("", environment.get("GIT_ASKPASS"));
        return environment;
    }

    @Test
    void aBuildsGitAsksNoAskpassProgramForACredential() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"x\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/r.git";
            // A configured askpass program must not run: Git runs one even under GIT_TERMINAL_PROMPT=0, and only
            // the empty GIT_ASKPASS a build sets ends the chain (GIT_ASKPASS, core.askPass, SSH_ASKPASS) first.
            var result = GitProcess.run(new ProcessBuilder("git", "-c", "credential.helper=", "-c", "core.askPass=/no/such/dialog",
                    "ls-remote", url).redirectErrorStream(true), null, true, GitTransferConfig.DEFAULT, p -> {});
            assertEquals(GitProcess.Status.FAILED, result.status());
            assertTrue(result.diagnostics().contains("terminal prompts disabled"), result.diagnostics());
            assertFalse(result.diagnostics().contains("askpass"), result.diagnostics());
            assertTrue(result.ssh().build());
            assertTrue(GitCommandException.promptNote(result.diagnostics(), result.ssh()).contains("no askpass program"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void updateAllLeavesTheUsersOwnSshAlone(@TempDir Path repo) throws Exception {
        // Not a build: kept as it is, pending Sanne's decision for update-all.
        GitTestUtils.git(repo, "init", "-q");
        GitTestUtils.git(repo, "config", "core.sshCommand", "ssh -i k");
        var environment = new HashMap<String, String>(Map.of("PATH", System.getenv("PATH")));
        assertNull(PreparationGit.nonInteractiveSsh(environment, repo, false));
        assertEquals(Map.of("PATH", System.getenv("PATH")), environment);
    }

    @Test
    void buildsAddBatchModeToTheUsersOwnSshCommand(@TempDir Path repo) throws Exception {
        GitTestUtils.git(repo, "init", "-q");
        GitTestUtils.git(repo, "config", "core.sshCommand", "ssh -i work_key");
        assertEquals("ssh -o BatchMode=yes -i work_key", sshCommandOfABuild(repo));
        GitTestUtils.git(repo, "config", "--unset", "core.sshCommand");
        assertEquals("ssh -o BatchMode=yes", sshCommandOfABuild(repo));
    }

    private static String sshCommandOfABuild(Path repo) throws Exception {
        return PreparationGit.run(repo, null, null,
                List.of("-c", "alias.sshcommand=!printenv GIT_SSH_COMMAND", "sshcommand"), GitTransferConfig.DEFAULT, p -> {});
    }

    @Test
    void batchModeGoesRightAfterSshSoNothingTheUserWroteCanUndoIt() {
        assertEquals("ssh -o BatchMode=yes -o BatchMode=no", PreparationGit.withBatchMode("ssh -o BatchMode=no"));
        assertEquals("ssh -o BatchMode=yes -i k # work", PreparationGit.withBatchMode("ssh -i k # work"));
        assertEquals("ssh -o BatchMode=yes\t-i k", PreparationGit.withBatchMode("ssh\t-i k"));
        assertEquals("/usr/bin/ssh -o BatchMode=yes", PreparationGit.withBatchMode(" /usr/bin/ssh "));
        assertNull(PreparationGit.withBatchMode("plink -batch"), "not plainly ssh: runs as it is");
        assertNull(PreparationGit.withBatchMode("\"/opt/my ssh/ssh\" -i k"));
    }

    @Test
    void localStepsLeaveTheSshCommandAloneAndReadNoConfig(@TempDir Path repo) throws Exception {
        GitTestUtils.git(repo, "init", "-q");
        GitTestUtils.git(repo, "config", "core.sshCommand", "ssh -i work_key");
        assertEquals("ssh -o BatchMode=yes", PreparationGit.run(repo, null, null,
                List.of("-c", "alias.sshcommand=!printenv GIT_SSH_COMMAND", "sshcommand")));
    }

    @Test
    void forwardedLinesAreScrubbedBeforeTheyAreCutAndBounded() {
        var longLine = "fatal: unable to access 'https://" + "x".repeat(170) + "glpat-secrettoken@gitlab.example.com/r.git/'";
        var details = GitCommandException.details(null, longLine + "\n\u001b[31mred\u001b[0m\n" + "line\n".repeat(30));
        assertFalse(String.join("\n", details).contains("glpat-secret"), details.toString());
        assertEquals(1 + GitProcess.TAIL_LINES, details.size(), "the last lines only");
        var bounded = GitCommandException.details(null, longLine + "\n\u001b[31mred\u001b[0m");
        assertEquals("  | red", bounded.getLast(), "no terminal escapes");
    }

    @Test
    void aStoppedPreparationTellsIsxsReasonApartFromGitsOutput() {
        var stopped = new PreparationGit.TransferStopped("Git preparation clone stalled: no output, I/O or CPU work for 5m",
                "Receiving objects:  45% (1/2)");
        assertEquals(List.of("Git's output:", "  | Receiving objects:  45% (1/2)"), stopped.details());
        assertTrue(stopped.getMessage().contains("stalled"));
    }

    @Test
    void aFailedPreparationCommandCarriesGitsOutputApartFromItsMessage(@TempDir Path repo) throws Exception {
        GitTestUtils.git(repo, "init", "-q");
        var failed = assertThrows(GitCommandException.class, () -> PreparationGit.run(repo, null, null,
                List.of("-c", "alias.fails=!echo 'fatal: could not read Username for x: terminal prompts disabled' >&2; exit 128",
                        "fails"), GitTransferConfig.DEFAULT, p -> {}));
        assertEquals("Git preparation failed: fails", failed.getMessage());
        assertEquals(List.of(
                "isx ran Git with GIT_TERMINAL_PROMPT=0 and no askpass program, so it could not ask for a username or password."
                        + " Configure a Git credential helper.",
                "Git's output:",
                "  | fatal: could not read Username for x: terminal prompts disabled"), failed.details());
    }
}
