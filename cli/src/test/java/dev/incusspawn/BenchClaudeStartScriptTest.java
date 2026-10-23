package dev.incusspawn;

import dev.incusspawn.tool.ClaudeSetup;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bench/claude-start.sh} times the Claude Code action's pre-launch scan inside an instance,
 * from its own copy of the command: the bench cannot ask isx for it. The copy must be the action
 * with Claude itself left out, or the figure measures a command no user runs.
 */
class BenchClaudeStartScriptTest {

    private static final Path SCRIPT = Path.of("../bench/claude-start.sh").toAbsolutePath();

    @Test
    void timesThePreLaunchTheActionRuns() throws Exception {
        var matcher = Pattern.compile("^PRE_LAUNCH = r\"\"\"(.*)\"\"\"$", Pattern.MULTILINE)
                .matcher(Files.readString(SCRIPT));
        assertTrue(matcher.find(), "no PRE_LAUNCH in " + SCRIPT);
        assertEquals(ClaudeSetup.actionCommand(":", ":"), matcher.group(1));
    }
}
