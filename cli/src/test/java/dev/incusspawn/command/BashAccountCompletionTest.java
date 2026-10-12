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

    /** Completes {@code line}; the cursor is at its end. Returns the line after readline applies each candidate. */
    private static final String TWO_ACCOUNTS = "github=\\ngithub=personal\\tdefault\\ngithub=acme\\n";

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
                [ "$1 $2" = "list -q" ] && echo box1
                exit 0
                """.formatted(accounts));
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
                complete("isx branch x --account github=", "github=\\ngithub=solo\\tdefault\\n"));
        assertEquals(List.of("isx account set box1 github=solo"),
                complete("isx account set box1 github=", "github=\\ngithub=solo\\tdefault\\n"));
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
}
