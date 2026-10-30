package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;

/** The closed set of worker adapters. No discovery or initialization outside the MCP path. */
enum DelegateAgent {
    CLAUDE("claude", "claude", "Claude Code"), CODEX("codex", "openai", "Codex"),
    /**
     * Pi spends Claude's account or OpenAI's by the provider its template chose (PiSetup), and
     * the proxy injects whichever it is. Its namespace is only read for a model preflight, which
     * Pi has none of, so it names none.
     */
    PI("pi", "", "Pi"),
    UNKNOWN("unknown", "", "an unknown agent");

    final String id;
    final String namespace;
    /** How a message to a coordinator names it. */
    final String label;

    DelegateAgent(String id, String namespace, String label) {
        this.id = id;
        this.namespace = namespace;
        this.label = label;
    }

    static DelegateAgent parse(String name) {
        return switch (name == null ? "" : name) {
            case "claude" -> CLAUDE;
            case "codex" -> CODEX;
            case "pi" -> PI;
            default -> UNKNOWN;
        };
    }

    static DelegateAgent select(InstanceBackend.TemplateInfo template, String requested) {
        var agent = requested == null ? template.defaultAgent() : parse(requested);
        if (agent == UNKNOWN || !template.tools().contains(agent.id)) {
            throw new ToolError(ToolError.Code.INVALID_ARGUMENT, "template '" + template.name()
                    + "' cannot delegate to " + (requested == null ? "an agent" : requested)
                    + "; choose a template with supports_delegate and an agent from list_templates.delegate_agents.");
        }
        return agent;
    }

    /** Whether it takes a turn budget: Claude's {@code --max-turns}; neither Codex nor Pi has one. */
    boolean supportsMaxTurns() {
        return this == CLAUDE;
    }

    /** Whether a chosen model is tried before launch ({@link ModelCheck}); for the others an access error is the task's. */
    boolean supportsModelPreflight() {
        return this == CLAUDE;
    }

    /**
     * Whether its runs go through a guest-side runner ({@code codex-runner.cjs},
     * {@code pi-runner.cjs}), which publishes {@code result-<run>.txt} and {@code outcome-<run>.json}.
     */
    boolean usesRunner() {
        return this == CODEX || this == PI;
    }

    String permissionMode(McpConfig config, String template) {
        try {
            return switch (this) {
                case CLAUDE -> config.delegatePermissionMode(template);
                case CODEX -> config.codexSandboxMode(template);
                case PI -> config.piPermissionMode(template);
                case UNKNOWN -> throw new IllegalArgumentException("unsupported task agent or metadata version");
            };
        } catch (IllegalArgumentException e) {
            throw new ToolError(ToolError.Code.REFUSED, e.getMessage());
        }
    }

    void requireBudget(McpConfig config, Tasks.Profile profile) {
        if (this == UNKNOWN) throw new ToolError(ToolError.Code.REFUSED, "unsupported task agent or metadata version; upgrade isx before continuing it");
        if (supportsMaxTurns()) return;
        if (profile.maxTurns() != null) throw new ToolError(ToolError.Code.INVALID_ARGUMENT, label + " does not support max_turns; omit it.");
        if (config.delegateMaxTurns() != null) throw new ToolError(ToolError.Code.REFUSED,
                label + " cannot enforce delegate-max-turns in mcp.yaml; the user must revise that policy before delegating to " + label + ".");
    }

    /** A CLI check inside the worker, with no inference and no credential reads. */
    String probe() {
        return switch (this) {
            case CODEX -> "command -v codex >/dev/null && command -v node >/dev/null"
                    + " && timeout 10 codex exec --help | grep -q -- --output-last-message"
                    + " && timeout 10 codex exec resume --help | grep -q -- --json";
            case PI -> "command -v pi >/dev/null && command -v node >/dev/null"
                    + " && timeout 10 pi --help | grep -q -- --session-id";
            default -> "command -v claude >/dev/null";
        };
    }

    /** What the coordinator is told when {@link #probe} fails. */
    String probeRefusal(String instance) {
        return switch (this) {
            case CODEX -> "Codex and Node with exec/resume JSON output are required in " + instance
                    + "; rebuild its template or upgrade the CLI.";
            case PI -> "Pi with --session-id and Node are required in " + instance
                    + "; rebuild its template or upgrade the CLI.";
            default -> "Claude Code is required in " + instance + "; rebuild its template.";
        };
    }
}
