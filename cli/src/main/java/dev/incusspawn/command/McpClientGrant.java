package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.proxy.McpClientCheck;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;

/**
 * The check after a branch granted MCP control (#915), the same from {@code isx branch} and the
 * TUI's branch dialog (#1811): that the instance reaches {@code isx mcp}. The
 * {@link dev.incusspawn.incus.Metadata#MCP_CALLER} grant itself, and the network it needs, are
 * {@code BranchFlow}'s.
 */
public final class McpClientGrant {

    private McpClientGrant() {}

    /**
     * Asks from inside the started branch {@code name} the way its Claude Code will ask, while the
     * user is here to see it, and reports what it found through {@link BuildOutput}.
     */
    public static void check(IncusClient incus, String name) {
        BuildOutput.stepStart("Checking isx mcp from " + name);
        var reached = McpClientCheck.run(incus, name);
        if (reached.ok()) BuildOutput.stepDone("isx " + OutputFormat.oneLine(reached.version()) + ", " + reached.tools() + " tools");
        else {
            // A warning, not a failed step: the branch is made either way, and the TUI keeps a
            // warning in its log, where the shell that follows cannot draw over it
            BuildOutput.stepBreak();
            BuildOutput.stepWarn(reached.describe(name));
        }
    }
}
