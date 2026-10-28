package dev.incusspawn.command;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated bash completion in a real bash with a fake {@code isx} and checks the line
 * readline would end up with. Readline splits words on {@code =} (COMP_WORDBREAKS) and replaces
 * only the last piece, so a candidate that repeats the typed {@code namespace=} doubles it.
 */
class BashAccountCompletionTest {

    private static final Path BASH_COMPLETION = Path.of("/usr/share/bash-completion/bash_completion");

    @TempDir
    Path dir;

    /** github with two accounts; claude configured, but with no usable account. */
    private static final String TWO_ACCOUNTS =
            "claude\\ngithub\\ngithub=\\ngithub=personal\\tdefault\\ngithub=acme\\n";

    /** What the fake {@code isx account add --completion} prints. */
    private Path addNames;

    /** What the fake {@code isx list -q}, {@code isx templates} and {@code isx tools list} print. */
    private Path instances, templates, toolNames;

    @org.junit.jupiter.api.BeforeEach
    void writeAddNames() throws Exception {
        addNames = Files.writeString(dir.resolve("add-names.txt"), "claude\ngh\ngithub\n");
        instances = Files.writeString(dir.resolve("instances.txt"), "box1\n");
        templates = Files.writeString(dir.resolve("templates.txt"), "tpl-dev\n");
        toolNames = Files.writeString(dir.resolve("tools.txt"), "podman\ntmux\n");
    }

    /** Completes {@code line}; the cursor is at its end. Returns the line after readline applies each candidate. */
    private List<String> complete(String line) throws Exception {
        return complete(line, TWO_ACCOUNTS);
    }

    /** {@code accounts} is what the fake {@code isx account list --completion} prints, printf-escaped. */
    private List<String> complete(String line, String accounts) throws Exception {
        Assumptions.assumeTrue(Files.exists(BASH_COMPLETION), "bash-completion not installed");
        var fake = dir.resolve("bin");
        Files.createDirectories(fake);
        var isx = fake.resolve("isx");
        Files.writeString(isx, """
                #!/bin/bash
                [ "$1 $2 $3" = "account list --completion" ] && printf '%s'
                [ "$1 $2 $3" = "account add --completion" ] && cat %s
                [ "$1 $2" = "list -q" ] && cat %s
                [ "$1" = "templates" ] && cat %s
                [ "$1 $2" = "tools list" ] && cat %s
                exit 0
                """.formatted(accounts, addNames, instances, templates, toolNames));
        isx.toFile().setExecutable(true);
        var script = dir.resolve("isx.bash");
        Files.writeString(script, CompletionCommand.rawScript(CompletionCommand.Shell.bash));
        var driver = dir.resolve("drive.sh");
        // COMP_WORDS as bash builds them: split at every "=" in COMP_WORDBREAKS.
        Files.writeString(driver, """
                source %s
                source %s
                line="$1"
                COMP_LINE="$line"; COMP_POINT=${#line}; COMP_TYPE=9; COMP_KEY=9
                words=(); rest="$line"
                while [[ -n "$rest" ]]; do
                  if [[ "$rest" == " "* ]]; then rest="${rest# }"; continue; fi
                  tok="${rest%%%%[ =]*}"
                  if [[ -z "$tok" ]]; then tok="${rest:0:1}"; fi
                  words+=("$tok"); rest="${rest:${#tok}}"
                done
                [[ "$line" == *" " ]] && words+=("")
                COMP_WORDS=("${words[@]}"); COMP_CWORD=$(( ${#words[@]} - 1 ))
                _isx
                # Readline replaces only the text after the last word break (space or "=").
                for r in "${COMPREPLY[@]}"; do echo "${line%%"${line##*[ =]}"}$r"; done
                """.formatted(BASH_COMPLETION, script));
        var pb = new ProcessBuilder("bash", "--norc", "--noprofile", driver.toString(), line);
        pb.environment().put("PATH", fake + ":" + System.getenv("PATH"));
        // %q's $'...' form depends on the locale: octal bytes in C, which the tests expect
        pb.environment().put("LC_ALL", "C");
        var output = dir.resolve("out.txt");
        pb.redirectErrorStream(true).redirectOutput(output.toFile());
        var p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("bash completion did not finish in 30s");
        }
        return Files.readString(output).lines().filter(l -> !l.isBlank()).toList();
    }

    @Test
    void accountOptionCompletesTheAccountWithoutRepeatingTheNamespace() throws Exception {
        assertEquals(List.of("isx branch x --account github=acme"), complete("isx branch x --account github=a"));
    }

    @Test
    void accountSetCompletesTheAccountWithoutRepeatingTheNamespace() throws Exception {
        assertEquals(List.of("isx account set box1 github=personal"), complete("isx account set box1 github=p"));
    }

    @Test
    void aBareNamespacePrefixOffersNamespaceAndAccounts() throws Exception {
        assertEquals(List.of("isx branch x --account github=", "isx branch x --account github=personal",
                "isx branch x --account github=acme"), complete("isx branch x --account gith"));
    }

    @Test
    void aTypedNamespaceWithOneAccountCompletesThatAccount() throws Exception {
        assertEquals(List.of("isx branch x --account github=solo"),
                complete("isx branch x --account github=", "github\\ngithub=\\ngithub=solo\\tdefault\\n"));
        assertEquals(List.of("isx account set box1 github=solo"),
                complete("isx account set box1 github=", "github\\ngithub=\\ngithub=solo\\tdefault\\n"));
    }

    @Test
    void aTypedNamespaceWithSeveralAccountsListsOnlyTheAccounts() throws Exception {
        assertEquals(List.of("isx branch x --account github=personal", "isx branch x --account github=acme"),
                complete("isx branch x --account github="));
    }

    @Test
    void theOptionEqualsFormCompletesTheAccount() throws Exception {
        assertEquals(List.of("isx branch x --account=github=acme"), complete("isx branch x --account=github=a"));
    }

    @Test
    void aNamespaceWithoutAUsableAccountIsNotOfferedForSelection() throws Exception {
        assertEquals(List.of(), complete("isx branch x --account cl"));
        assertEquals(List.of(), complete("isx account set box1 cl"));
    }

    @Test
    void unsetOffersEveryNamespace() throws Exception {
        assertEquals(List.of("isx account unset box1 claude", "isx account unset box1 github"),
                complete("isx account unset box1 "));
    }

    /** add takes a tool as well as a namespace, so gh is offered beside github (#1773). */
    @Test
    void addOffersToolNamesBesideNamespaces() throws Exception {
        assertEquals(List.of("isx account add gh", "isx account add github"), complete("isx account add g"));
    }

    /**
     * A cloned repository's tool named like a command substitution is never run on TAB: what
     * {@code isx account add --completion} prints for it goes through compgen -W, which expands it.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(IsolatedHome.class)
    void addNeverRunsAHostileToolName() throws Exception {
        var marker = dir.resolve("pwned");
        var hostile = "$(touch " + marker + ")";
        var tools = AccountCompletionTest.withHostileTool(hostile);
        Files.writeString(addNames, String.join("\n", AccountCommand.addCompletionLines(tools)) + "\n");
        complete("isx account add ");
        assertFalse(Files.exists(marker), "completion ran the tool's name as a command");
    }

    @Test
    void toolsShowCompletesToolNames() throws Exception {
        assertEquals(List.of("isx tools show podman"), complete("isx tools show po"));
    }

    /**
     * The issue's reproduction (#1778): a tool named like a command substitution, loaded and
     * listed as {@code isx tools list} prints it, never runs on TAB after {@code isx tools show}.
     * The loader refuses the name, so this holds with either layer; {@link #noSourceIsExpandedOnTab}
     * covers the script on its own.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(IsolatedHome.class)
    void toolsShowNeverRunsAHostileToolName() throws Exception {
        var marker = dir.resolve("pwned");
        var tools = Files.createDirectories(dev.incusspawn.Environment.configDir().resolve("tools"));
        Files.writeString(tools.resolve("evil.yaml"), """
                name: "$(touch %s)"
                description: x
                """.formatted(marker));
        var loader = new dev.incusspawn.tool.ToolDefLoader();
        var listed = new java.io.ByteArrayOutputStream();
        ToolsCommand.ListSub.printTable(new java.io.PrintStream(listed, true), new java.util.TreeMap<>(loader.allToolSetups()),
                loader::getSource, false);
        Files.writeString(toolNames, listed.toString());
        complete("isx tools show ");
        assertFalse(Files.exists(marker), "completion ran the tool's name as a command");
    }

    /**
     * No dynamic source is expanded by the script, whatever a future source lets through: each
     * one here prints a command substitution, and TAB at every place that offers it runs none.
     */
    @Test
    void noSourceIsExpandedOnTab() throws Exception {
        var marker = dir.resolve("pwned");
        var hostile = "$(touch " + marker + ")\n`touch " + marker + "`\n";
        for (var source : List.of(addNames, instances, templates, toolNames)) {
            Files.writeString(source, hostile);
        }
        var accounts = "x\\n$(touch " + marker + ")\\nx=$(touch " + marker + ")\\n";
        for (var line : List.of("isx tools show ", "isx account add ", "isx account show ", "isx account unset box1 ",
                "isx account set box1 ", "isx branch x --account ", "isx branch x --from ", "isx build ",
                "isx destroy ", "isx shell ", "isx run ", "isx templates ", "isx templates edit ",
                "isx project update ", "isx clean ", "isx build tpl-dev ", "isx account reset-proofs ")) {
            complete(line, accounts);
            assertFalse(Files.exists(marker), "completion of '" + line + "' ran a candidate as a command");
        }
    }

    /** An account is quoted after the typed namespace is dropped from it, so the namespace is not repeated. */
    @Test
    void anAccountIsInsertedQuotedAfterItsNamespace() throws Exception {
        assertEquals(List.of("isx account set box1 ns=a\\,b"), complete("isx account set box1 ns=", "ns\\nns=\\nns=a,b\\n"));
    }

    /**
     * An account %q writes as $'...' (a U+2028 in it) still loses the typed namespace: dropped
     * from the name before quoting, not from the quoted word, where it would be missed.
     */
    @Test
    void anAccountQuotedAsAnsiCLosesItsNamespaceToo() throws Exception {
        var accounts = "ns\\nns=\\nns=a\u2028b\\n";
        // The driver runs bash under LC_ALL=C, where %q writes the bytes in octal
        var inserted = "isx account set box1 ns=$'a\\342\\200\\250b'";
        assertEquals(List.of(inserted), complete("isx account set box1 ns=", accounts));
    }

    /** A typed namespace offers only its own accounts, never another namespace's after it. */
    @Test
    void aTypedNamespaceOffersNoOtherNamespacesAccounts() throws Exception {
        var accounts = "claude\\ngithub\\nclaude=\\nclaude=work\\ngithub=\\ngithub=personal\\n";
        assertEquals(List.of("isx account set box1 github=personal"), complete("isx account set box1 github=", accounts));
        assertEquals(List.of("isx branch x --account claude=work"), complete("isx branch x --account claude=", accounts));
    }

    /** A candidate that is a glob is not expanded to the files in the current directory, and is inserted quoted. */
    @Test
    void aGlobCandidateIsNotExpanded() throws Exception {
        Files.writeString(toolNames, "*\n");
        assertEquals(List.of("isx tools show \\*", "isx tools show --help"), complete("isx tools show "));
    }

    /**
     * Readline inserts a candidate as is, so one holding a command substitution is quoted: it
     * would otherwise land on the command line and run on Enter.
     */
    @Test
    void aCandidateIsInsertedQuoted() throws Exception {
        // A path %q leaves as it is: the temporary directory's name is letters, digits and '/'
        var marker = dir.resolve("pwned");
        Files.writeString(toolNames, "$(touch " + marker + ") y\n");
        var inserted = "isx tools show \\$\\(touch\\ " + marker + "\\)\\ y";
        assertEquals(List.of(inserted, "isx tools show --help"), complete("isx tools show "));
        // What a first TAB inserted still matches when TAB is pressed again
        assertEquals(List.of(inserted), complete("isx tools show \\$\\(to"));
        assertFalse(Files.exists(marker));
    }
}
