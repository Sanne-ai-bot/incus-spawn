package dev.incusspawn.command;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static dev.incusspawn.command.IsolatedHome.*;
import static dev.incusspawn.util.BuildOutput.stripAnsi;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx account add} configures one credential with the step {@code isx init} runs for it,
 * and runs nothing else: no package, firewall or service step, so no sudo (#1469).
 */
@ExtendWith(IsolatedHome.class)
class AccountAddTest {

    private static final Pattern COUNTER = Pattern.compile("\\[(\\d+)/(\\d+)]");

    /** Init whose credential steps read {@code prompts}; every other step would fail the test. */
    static final class ScriptedInit extends InitCommand {
        Prompts prompts;

        @Override
        Prompts prompts() {
            return prompts;
        }

        @Override
        int runHostQuiet(String... command) {
            throw new AssertionError("account add ran a host command: " + String.join(" ", command));
        }
    }

    private record Run(int exit, String out, String err) {}

    private static Run add(String credential, Prompts prompts) throws Exception {
        var out = System.out;
        var err = System.err;
        var outBuffer = new ByteArrayOutputStream();
        var errBuffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuffer, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
        try {
            var init = new ScriptedInit();
            init.prompts = prompts;
            var exit = init.addCredential(credential);
            return new Run(exit, stripAnsi(outBuffer.toString(StandardCharsets.UTF_8)),
                    stripAnsi(errBuffer.toString(StandardCharsets.UTF_8)));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    /** A user's own YAML tool, owning the namespace {@code acmeCloud}, as init would find it. */
    private static void acme() throws Exception {
        var dir = Files.createDirectories(Environment.configDir().resolve("tools"));
        Files.writeString(dir.resolve("acme.yaml"), """
                name: acme
                description: Acme Cloud CLI
                proxy:
                  config-namespace: acmeCloud
                  configuration:
                    api-key:
                      config-path: "apiKey"
                      description: Acme API key
                      secret: true
                """);
    }

    @Test
    void theNamespaceRunsThatCredentialsStepAlone() throws Exception {
        acme();
        var prompts = new ScriptedPrompts().secret("acme-secret");

        var run = add("acmeCloud", prompts);

        assertEquals(0, run.exit(), run.err());
        prompts.assertFullyConsumed();
        assertEquals("acme-secret", saved("acmeCloud.accounts.default.apiKey"));
        var counter = COUNTER.matcher(run.out());
        assertTrue(counter.find(), run.out());
        assertEquals("[1/1]", counter.group(), run.out());
        assertFalse(counter.find(), "only one step: " + run.out());
        assertFalse(run.out().contains("Credential Setup"), run.out());
    }

    @Test
    void theToolNameWorksToo() throws Exception {
        acme();
        var prompts = new ScriptedPrompts().secret("acme-secret");

        assertEquals(0, add("acme", prompts).exit());
        assertEquals("acme-secret", saved("acmeCloud.accounts.default.apiKey"));
    }

    /** A configured credential opens on init's account menu, so named accounts are added the same way. */
    @Test
    void aSecondAccountIsAddedThroughTheSameMenu() throws Exception {
        acme();
        seed("acmeCloud:\n  apiKey: \"acme-first\"\n");
        var prompts = new ScriptedPrompts().line("a", "work").secret("acme-work");

        assertEquals(0, add("acmeCloud", prompts).exit());
        prompts.assertFullyConsumed();
        assertEquals("acme-first", saved("acmeCloud.accounts.default.apiKey"));
        assertEquals("acme-work", saved("acmeCloud.accounts.work.apiKey"));
    }

    /** Without a name it is init's credential menu, then the steps picked there. */
    @Test
    void withoutANameItOffersInitsCredentialMenu() throws Exception {
        acme();
        var run = add(null, ScriptedPrompts.lines(""));

        assertEquals(0, run.exit(), run.err());
        assertTrue(run.out().contains("Credential Setup"), run.out());
        assertTrue(run.out().contains("Acme Cloud CLI"), run.out());
        assertNothingSaved();
    }

    @Test
    void anUnknownNameIsRefusedWithTheNamesThereAre() throws Exception {
        acme();
        var run = add("nope", new ScriptedPrompts());

        assertEquals(1, run.exit());
        assertTrue(run.err().contains("no credential named 'nope'"), run.err());
        assertTrue(run.err().contains("acmeCloud (acme)"), run.err());
        assertTrue(run.err().contains("github (gh)"), run.err());
        assertTrue(run.err().contains("claude"), run.err());
        assertNothingSaved();
    }

    /** A tool with a step in a namespace another step owns is reached, and listed, by its own name. */
    @Test
    void aToolInAnOwnedNamespaceIsListedByItsName() throws Exception {
        var dir = Files.createDirectories(Environment.configDir().resolve("tools"));
        Files.writeString(dir.resolve("aider.yaml"), """
                name: aider
                proxy:
                  config-namespace: openai
                  configuration:
                    org: { config-path: org, description: OpenAI organization, secret: true }
                """);
        var run = add("nope", new ScriptedPrompts());

        assertTrue(run.err().contains(" aider,"), run.err());
        assertFalse(run.err().contains("openai (aider)"), run.err());
        assertTrue(run.err().contains("openai (codex)"), run.err());
    }

    /** A tool that borrows another's credential has no step: its owner's is the one to run. */
    @Test
    void aToolThatBorrowsItsCredentialIsNotAName() throws Exception {
        var run = add("copilot", new ScriptedPrompts());

        assertEquals(1, run.exit());
        assertTrue(run.err().contains("no credential named 'copilot'"), run.err());
    }

    @Test
    void withoutATerminalItSaysSoAndChangesNothing() throws Exception {
        acme();
        var run = add("acmeCloud", null);

        assertEquals(1, run.exit());
        assertTrue(run.err().contains("needs a terminal"), run.err());
        assertNothingSaved();
    }
}
