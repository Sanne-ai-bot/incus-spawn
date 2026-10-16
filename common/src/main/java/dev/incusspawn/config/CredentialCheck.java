package dev.incusspawn.config;

import dev.incusspawn.proxy.InheritedProof;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Whether an instance about to be branched has the credentials its tools need, answered for the
 * accounts it will actually use -- template pins, the source's own pins and any {@code --account}
 * alike (#793) -- so a gap is reported before anything is created rather than as a failed API
 * call inside the container.
 *
 * <p>Credentials are declared, not listed. Each tool the template chain names, and everything it
 * {@code requires:}, spends its {@linkplain ToolSetup#credentialNamespaces(Map) credential
 * namespaces}; the {@code secret: true} entries of the tools serving those must resolve, and so
 * must the tool's own, borrowed ones included (Copilot's {@code github.token}). A tool whose
 * readiness is more than a key being set says so in {@link ToolSetup#credentialProblem}, as
 * Claude's typed accounts do.
 */
public final class CredentialCheck {

    private CredentialCheck() {}

    /**
     * A message naming what is missing, or {@code ""} when everything is configured.
     *
     * @param template  the template the instance was built from
     * @param selection the instance's effective account selection, namespace to account
     * @param loader    where tool definitions come from; the caller's, so they are not re-read
     */
    public static String check(SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                               Map<String, String> selection, ToolDefLoader loader) {
        return check(config, template, allDefs, selection, loader.allToolSetups(),
                ToolProxyResolver.proxyToolSetups(config, loader));
    }

    /**
     * As {@link #check(SpawnConfig, ImageDef, Map, Map, ToolDefLoader)}, from the tool setups a
     * caller already resolved.
     *
     * @param allTools every known tool, to follow {@code requires:}
     * @param served   the tools the proxy serves credentials for ({@link ToolProxyResolver#proxyToolSetups})
     */
    public static String check(SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                               Map<String, String> selection, Map<String, ToolSetup> allTools,
                               Map<String, ToolSetup> served) {
        try {
            var gaps = gaps(config.tree(), AccountSelection.byNamespace(served), config, template, allDefs,
                    selection, allTools, served);
            if (!gaps.inherited().isEmpty() && InheritedProof.parentSecret() == null) {
                return InheritedProof.notNestedMessage(gaps.inherited().getFirst());
            }
            if (gaps.missing().isEmpty()) return "";
            return "Missing credentials: " + String.join(", ", gaps.missing()) + ". Run 'isx init' to configure.";
        } catch (AccountResolver.UnknownAccountException e) {
            // Callers validate the selection first; this only keeps a stale one from escaping.
            return e.getMessage();
        }
    }

    /**
     * Each credential missing for any of {@code templates}, mapped to the templates that need it, in
     * the order given; empty when nothing is missing. Each template is checked for the accounts its
     * definition selects, which is what a branch of it starts with -- so this names, before a
     * build or at the end of init, what {@code isx branch} will refuse (#1438). A template whose
     * selection is invalid or inherited is left to the build and the branch to report.
     */
    public static Map<String, List<String>> missingByCredential(SpawnConfig config, Collection<String> templates,
                                                                Map<String, ImageDef> allDefs, ToolDefLoader loader) {
        return missingByCredential(config, templates, allDefs, loader.allToolSetups(),
                ToolProxyResolver.proxyToolSetups(config, loader));
    }

    /**
     * As {@link #missingByCredential(SpawnConfig, Collection, Map, ToolDefLoader)}, from the tool
     * setups a caller already resolved.
     */
    public static Map<String, List<String>> missingByCredential(SpawnConfig config, Collection<String> templates,
                                                                Map<String, ImageDef> allDefs,
                                                                Map<String, ToolSetup> allTools,
                                                                Map<String, ToolSetup> served) {
        // Once for every template: the tree serializes the whole config
        var tree = config.tree();
        var owners = AccountSelection.byNamespace(served);
        var byCredential = new LinkedHashMap<String, List<String>>();
        for (var name : templates) {
            var template = allDefs.get(name);
            if (template == null) continue;
            try {
                var selection = ImageDef.resolveAccounts(template, allDefs);
                for (var credential : gaps(tree, owners, config, template, allDefs, selection, allTools, served).missing()) {
                    byCredential.computeIfAbsent(credential, c -> new ArrayList<>()).add(name);
                }
            } catch (AccountResolver.UnknownAccountException e) {
                // Not this check's to report: the build refuses an unknown account by itself
            }
        }
        return byCredential;
    }

    /** What is missing, and the namespaces that are the parent instance's instead. */
    private record Gaps(List<String> missing, List<String> inherited) {}

    private static Gaps gaps(JqValue tree, Map<String, ToolSetup> owners, SpawnConfig config, ImageDef template, Map<String, ImageDef> allDefs,
                             Map<String, String> selection, Map<String, ToolSetup> allTools,
                             Map<String, ToolSetup> served) {
        // Leaf first, so a child's params win over an ancestor's for the same tool.
        var toolRefs = new LinkedHashMap<String, ToolDef.ToolRef>();
        for (var layer : ImageDef.chain(template, allDefs).reversed()) {
            for (var toolRef : layer.getTools()) toolRefs.putIfAbsent(toolRef.getName(), toolRef);
        }
        var tools = new LinkedHashSet<String>();
        toolRefs.keySet().forEach(name -> ToolSetup.addWithRequires(name, allTools, tools));

        var secretsOf = new LinkedHashSet<>(tools);
        var missing = new LinkedHashSet<String>();
        // An inherited credential is the parent instance's, whatever this config holds: its
        // tool's own check is not asked, and there must be a parent instead
        var inherited = new LinkedHashSet<String>();
        for (var name : tools) {
            var tool = allTools.get(name);
            if (tool == null) continue;
            var ref = toolRefs.get(name);
            // Resolved as the build resolves them: defaults applied, a null value included.
            // An invalid value is the build's to report, so what did resolve is used.
            var params = dev.incusspawn.tool.ParameterResolver.resolve(tool.parameters(),
                    ref == null ? Map.of() : ref.getParams()).resolvedValues();
            var namespaces = tool.credentialNamespaces(params);
            var own = 0;
            for (var namespace : namespaces) {
                var owner = owners.get(namespace);
                if (owner != null) secretsOf.add(owner.name());
                if (AccountResolver.inherits(tree, namespace, selection.get(namespace))) inherited.add(namespace);
                else own++;
            }
            if (own == 0 && !namespaces.isEmpty()) continue;
            var problem = tool.credentialProblem(config, params, selection);
            if (!problem.isEmpty()) missing.add(problem);
        }
        for (var secret : ToolProxyResolver.missingSecrets(tree, served, secretsOf, selection)) {
            missing.add(secret.description().isBlank() ? secret.path() : secret.description());
        }
        return new Gaps(List.copyOf(missing), List.copyOf(inherited));
    }
}
