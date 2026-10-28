package dev.incusspawn.lifecycle;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.Output;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;

/**
 * What isx does on the host for an instance whose template installs a given tool (#1720): the host
 * half of a tool, such as registering the instance with the host's herdr. The guest half is the
 * tool's own definition. This is the case of an orchestrator with no lifecycle hooks of its own,
 * which only knows a list of machines: isx tells it about the instances it should list.
 *
 * <p>Built-in Java only, never declared by a definition: a tool definition a cloned repository
 * ships must not make isx run commands on the host. And an instance whose build used a
 * project-local definition, or whose lineage is untrusted, gets none, since its tool of that name
 * may be the project's own ({@link #trusted}).
 */
public interface HostIntegration {

    /** The tool whose presence in the instance's build turns this on. */
    String tool();

    /**
     * Whether there is nothing to do for this instance any more: asked before the build is parsed,
     * so a start of an instance already done costs nothing but this answer.
     */
    boolean done(String name);

    /**
     * After the instance has started for use: its branch's start, or any later start that
     * prepares it ({@code isx shell}, {@code isx start}, the TUI, {@code isx mcp}). Called on
     * every such start, so it must cost nothing once done; {@code instance} is a read the caller
     * already holds. Never throws: what failed is a warning to {@code out}.
     */
    void started(IncusClient incus, String name, JqValue instance, Output out);

    /** After the instance was deleted, whatever its template: its config is gone. Never throws. */
    void destroyed(String name, Output out);

    /** The built-in host integrations; the directory is looked up per call, never at image build time. */
    List<HostIntegration> BUILT_IN = List.of(new HerdrIntegration(HostCommands.SYSTEM, dev.incusspawn.Environment::herdrMachinesDir));

    /** {@link #started} of every integration whose tool the instance's build installed. */
    static void startedAll(IncusClient incus, String name, JqValue instance, Output out) {
        if (instance == null) return;
        var buildSource = instance.path("config").pathText(Metadata.BUILD_SOURCE, "");
        for (var integration : BUILT_IN) {
            if (integration.done(name)) continue;
            if (BuildSource.installs(buildSource, integration.tool())) integration.started(incus, name, instance, out);
        }
    }

    /** {@link #destroyed} of every integration. */
    static void destroyedAll(String name, Output out) {
        for (var integration : BUILT_IN) integration.destroyed(name, out);
    }

    /**
     * Whether a host integration may act for {@code instance}: built from trusted definitions
     * only, with a trusted lineage. A project-local tool may share a built-in's name.
     */
    static boolean trusted(JqValue instance) {
        var config = instance.path("config");
        return Metadata.untrustedReason(key -> config.pathText(key, "")).isEmpty()
                && !BuildSource.usedProjectLocal(config.pathText(Metadata.BUILD_SOURCE, ""));
    }
}
