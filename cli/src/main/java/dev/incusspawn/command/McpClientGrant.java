package dev.incusspawn.command;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.McpScope;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
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
     * What a coordinator branched as {@code preflight} describes may control (#1812), judged as
     * {@code isx mcp} will judge it; null when {@code mcp.yaml} cannot be read, which is then said
     * as a warning.
     */
    public static McpScope scope(BranchFlow.Preflight preflight) {
        McpConfig policy;
        try {
            policy = McpConfig.load();
        } catch (McpConfig.Invalid e) {
            BuildOutput.warn(e.getMessage(), "isx mcp refuses every call until it is fixed.");
            return null;
        }
        var source = preflight.sourceInstance().path("config");
        var untrusted = Metadata.untrustedReason(key -> source.pathText(key, ""));
        if (untrusted.isEmpty() && BuildSource.usedProjectLocal(source.pathText(Metadata.BUILD_SOURCE, ""))) {
            untrusted = "built from a project-local definition";
        }
        // The trusted definitions isx mcp reads, not the branch's, which may hold the working directory's
        return McpScope.coordinator(preflight.template(), untrusted.isEmpty() ? null : untrusted, ImageDef.loadTrusted(),
                policy.templates());
    }

    /** Says what the coordinator may control, and warns of each way the two lists disagree. */
    public static void reportScope(McpScope scope) {
        if (scope == null) return;
        BuildOutput.step("MCP control: " + scope.summary());
        scope.mismatches().forEach(BuildOutput::warn);
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
