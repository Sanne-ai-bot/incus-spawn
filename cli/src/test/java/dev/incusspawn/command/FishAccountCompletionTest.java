package dev.incusspawn.command;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated fish completion in a real fish with a fake {@code isx} and checks the
 * candidates {@code complete -C} returns for a line, descriptions dropped.
 */
class FishAccountCompletionTest {

    @TempDir
    Path dir;

    private static final String FAKE_ISX = """
            #!/bin/bash
            case "$1 $2 $3" in
              "list -q ") echo box1 ;;
              "account list --completion") printf 'claude\\ngithub\\ngithub=\\ngithub=personal\\tdefault\\n' ;;
              "account add --completion") printf 'claude\\ngh\\ngithub\\n' ;;
            esac
            exit 0
            """;

    private List<String> complete(String line) throws Exception {
        return complete(line, FAKE_ISX);
    }

    /** {@code fakeIsx} is the script the fake {@code isx} runs. */
    private List<String> complete(String line, String fakeIsx) throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        var isx = bin.resolve("isx");
        Files.writeString(isx, fakeIsx);
        isx.toFile().setExecutable(true);
        var script = dir.resolve("isx.fish");
        Files.writeString(script, CompletionCommand.rawScript(CompletionCommand.Shell.fish));
        var pb = new ProcessBuilder("fish", "--no-config", "-c",
                "source $argv[1]; complete -C $argv[2]", script.toString(), line);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        // fish keeps variables and history under these; none of it belongs in the real home.
        for (var variable : List.of("HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME")) {
            pb.environment().put(variable, dir.toString());
        }
        var output = dir.resolve("out.txt");
        pb.redirectErrorStream(true).redirectOutput(output.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            Assumptions.abort("fish is not installed");
            throw e;
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("fish completion did not finish in 30s");
        }
        assertEquals(0, p.exitValue(), () -> "fish failed: " + readQuietly(output));
        return Files.readString(output).lines().map(l -> l.split("\t", 2)[0]).toList();
    }

    /** add takes a tool as well as a namespace, so gh is offered beside github (#1773). */
    @Test
    void addOffersToolNamesBesideNamespaces() throws Exception {
        assertEquals(List.of("gh", "github"), complete("isx account add g"));
    }

    @Test
    void unsetStillOffersOnlyNamespaces() throws Exception {
        assertEquals(List.of("claude", "github"), complete("isx account unset box1 "));
    }

    /** A name isx printed is offered as typed and never run: each source here prints a command substitution (#1778). */
    @Test
    void noSourceIsExpanded() throws Exception {
        var marker = dir.resolve("pwned");
        var hostile = """
                #!/bin/bash
                printf '%%s\\n' '$(touch %s)' '(touch %s)' '`touch %s`'
                """.formatted(marker, marker, marker);
        assertTrue(complete("isx tools show ", hostile).contains("$(touch " + marker + ")"), "offered as typed");
        for (var line : List.of("isx tools show ", "isx account add ", "isx account unset box1 ",
                "isx account set box1 ", "isx branch x --account ", "isx build ", "isx destroy ", "isx shell ")) {
            complete(line, hostile);
            assertFalse(Files.exists(marker), "completion of '" + line + "' ran a candidate as a command");
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "(no output)";
        }
    }
}
