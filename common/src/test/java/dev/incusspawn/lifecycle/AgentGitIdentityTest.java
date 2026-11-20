package dev.incusspawn.lifecycle;

import dev.incusspawn.config.GitIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fallback Git identity of a build (#2410): written to {@code /etc/gitconfig}, so the GitHub
 * account's identity in {@code ~/.gitconfig} wins wherever there is one.
 */
class AgentGitIdentityTest {

    private static final GitIdentity JANE = new GitIdentity("Jane Doe", "jane@example.org");

    @TempDir
    Path dir;

    @Test
    void theFallbackIsUsedWhereNoAccountGaveAnIdentity() throws Exception {
        var effective = run(JANE);
        assertEquals(new AgentGitIdentity.Effective(JANE, AgentGitIdentity.Source.CONFIG), effective);
        assertEquals("Agents commit as Jane Doe <jane@example.org>, from the git-identity in config.yaml",
                AgentGitIdentity.report(effective));
    }

    @Test
    void theAccountsIdentityWinsOverTheFallback() throws Exception {
        Files.writeString(home().resolve(".gitconfig"), "[user]\n\tname = agent-bot\n\temail = bot@example.com\n");
        var effective = run(JANE);
        assertEquals(new AgentGitIdentity.Effective(new GitIdentity("agent-bot", "bot@example.com"),
                AgentGitIdentity.Source.USER), effective);
        // Still written, for when the account's identity goes
        assertTrue(Files.readString(system()).contains("Jane Doe"));
    }

    @Test
    void aRemovedKeyTakesIsxsFallbackOutButNotATemplatesOwn() throws Exception {
        run(JANE);
        assertNull(run(null));
        assertFalse(Files.readString(system()).contains("Jane"));

        Files.writeString(system(), "[user]\n\tname = Template\n\temail = t@example.org\n");
        var effective = run(null);
        // A template's own, never named as config.yaml's
        assertEquals(new AgentGitIdentity.Effective(new GitIdentity("Template", "t@example.org"),
                AgentGitIdentity.Source.OTHER), effective);
        assertTrue(Files.readString(system()).contains("Template"));

        // Nor overwritten while the key is set
        assertEquals(new AgentGitIdentity.Effective(new GitIdentity("Template", "t@example.org"),
                AgentGitIdentity.Source.OTHER), run(JANE));
        assertFalse(Files.readString(system()).contains("Jane"));
    }

    @Test
    void aNameIsWrittenAsItIsNeverRunByTheShell() throws Exception {
        var hostile = new GitIdentity("$(touch pwned) 'x' \"y\"", "a;b@example.org");
        assertEquals(hostile, run(hostile).identity());
        assertFalse(Files.exists(dir.resolve("pwned")));
    }

    @Test
    void noIdentityIsSaidAsNone() {
        assertNull(AgentGitIdentity.parse("git\n"));
        assertNull(AgentGitIdentity.parse("git\nfile:/etc/gitconfig\tJane\n"));
        assertTrue(AgentGitIdentity.report(null).startsWith("Agents have no Git identity"));
    }

    @Test
    void theOriginNamesTheSource() {
        assertEquals(AgentGitIdentity.Source.CONFIG, AgentGitIdentity.sourceOf("file:/etc/gitconfig"));
        assertEquals(AgentGitIdentity.Source.USER, AgentGitIdentity.sourceOf("file:/home/agentuser/.gitconfig"));
        assertEquals(AgentGitIdentity.Source.OTHER, AgentGitIdentity.sourceOf("file:/home/agentuser/.config/git/config"));
    }

    private Path home() throws Exception {
        return Files.createDirectories(dir.resolve("home"));
    }

    private Path system() {
        return dir.resolve("gitconfig");
    }

    /**
     * The guest script, as root would run it, with its two paths moved under {@link #dir}: the
     * script's own text names them only as the constants it is built from.
     */
    private AgentGitIdentity.Effective run(GitIdentity identity) throws Exception {
        var script = AgentGitIdentity.script(identity)
                .replace("'" + AgentGitIdentity.SYSTEM_FILE + "'", "'" + system() + "'")
                .replace("HOME=" + AgentGitIdentity.AGENT_HOME, "HOME=" + home() + " XDG_CONFIG_HOME=" + home() + "/.config GIT_CONFIG_SYSTEM=" + system());
        var process = new ProcessBuilder("sh", "-c", script).directory(dir.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), out);
        // The real file's origin, as the guest reports it
        return AgentGitIdentity.parse(out.replace("file:" + system(), "file:" + AgentGitIdentity.SYSTEM_FILE)
                .replace("file:" + home() + "/.gitconfig", "file:" + AgentGitIdentity.AGENT_HOME + "/.gitconfig"));
    }
}
