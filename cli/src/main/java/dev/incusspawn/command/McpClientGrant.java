package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.McpClientCheck;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;

import java.util.Map;

/**
 * Granting a branch MCP control (#915), the same way from {@code isx branch --mcp-client} and the
 * TUI's branch dialog (#1811): the {@link Metadata#MCP_CALLER} stamp, and the check after the
 * branch that the instance reaches {@code isx mcp}. The network it needs is {@code BranchFlow}'s
 * to enforce ({@code mcpClientUnreachable}).
 */
public final class McpClientGrant {

    private McpClientGrant() {}

    /** The {@code BranchFlow.Request.extraConfig} for a branch granted MCP control, or not. */
    public static Map<String, String> extraConfig(boolean granted) {
        return granted ? Map.of(Metadata.MCP_CALLER, Metadata.newMcpCallerGrant()) : Map.of();
    }

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
