package dev.incusspawn.lifecycle;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1811: the {@link Metadata#MCP_CALLER} grant needs a route to the proxy, so an airgapped branch
 * asking for it is refused by {@code BranchFlow} itself, whichever front end built the request.
 * #1812: a template's {@code coordinator:} makes its branches coordinators, each with a
 * grant of its own, unless the request says otherwise.
 */
@ExtendWith({TempHome.class, LinuxBranching.class})
class BranchMcpClientTest {

    /** As {@code isx branch --mcp-client}. */
    private static BranchFlow.Request request(NetworkMode network) {
        return new BranchFlow.Request("tpl-dev", "coord", null, null, network, null, null, null, null,
                List.of(), false, Map.of(), true);
    }

    @Test
    void anAirgappedGrantIsRefusedBeforeAnyIncusRequest() {
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of());
        var e = assertThrows(BranchFlow.BranchException.class,
                () -> BranchFlow.preflight(daemon.client(), request(NetworkMode.AIRGAP), Map.of()));
        assertEquals("an airgapped instance cannot reach mcp.isx.internal.", e.getMessage());
        assertEquals(List.of(), daemon.requests());
    }

    @Test
    void aGrantTheCallerStampsItselfNeverReachesTheBranch() {
        // Only the request's choice decides; BranchFlow mints the grant, and strips any other
        var daemon = coordinatorSource();
        var request = new BranchFlow.Request("tpl-coord", "plain", false, false, NetworkMode.AIRGAP, null, null, null,
                null, List.of(), false, Map.of(Metadata.MCP_CALLER, Metadata.newMcpCallerGrant()));
        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));
        assertFalse(daemon.instance("plain").path("config").has(Metadata.MCP_CALLER));
    }

    @Test
    void aGrantedBranchGetsAGrantBranchFlowMinted() {
        var daemon = coordinatorSource();
        var stale = Metadata.newMcpCallerGrant();
        var request = new BranchFlow.Request("tpl-coord", "coord", false, false, NetworkMode.FULL, null, null, null,
                null, List.of(), false, Map.of(Metadata.MCP_CALLER, stale), true);
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
        var grant = daemon.instance("coord").path("config").path(Metadata.MCP_CALLER).asText("");
        assertTrue(Metadata.isMcpCallerGrant(grant), grant);
        assertNotEquals(stale, grant);
    }

    @Test
    void aTaintedTemplateOrAnInstanceSharingADefinitionsNameIsNoCoordinatorByDefault() {
        var defs = coordinatorTemplate(true);
        var tainted = new FakeIncusDaemon().container("tpl-coord", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.UNTRUSTED_LINEAGE, "a failed build"));
        assertFalse(BranchFlow.defaultsFor("tpl-coord", tainted.client().instanceMetadata("tpl-coord"), defs).mcpClient());
        var instance = new FakeIncusDaemon().container("tpl-coord", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        assertFalse(BranchFlow.defaultsFor("tpl-coord", instance.client().instanceMetadata("tpl-coord"), defs).mcpClient());
    }

    @Test
    void onlyAirgappedIsUnreachable() {
        assertNull(BranchFlow.mcpClientUnreachable(NetworkMode.FULL));
        assertNull(BranchFlow.mcpClientUnreachable(NetworkMode.PROXY_ONLY));
        assertNotNull(BranchFlow.mcpClientUnreachable(NetworkMode.AIRGAP));
    }

    /** {@code tpl-coord}, saying {@code isx-control-capability: <capability>} beside {@code control-over: [tpl-dev]}, or nothing. */
    private static Map<String, ImageDef> coordinatorTemplate(Boolean capability) {
        var def = new ImageDef();
        def.setName("tpl-coord");
        if (capability != null) def.setCoordinator(coordinator(capability, List.of("tpl-dev")));
        return Map.of("tpl-coord", def);
    }

    private static ImageDef.Coordinator coordinator(Boolean capability, List<String> controlOver) {
        var coordinator = new ImageDef.Coordinator();
        coordinator.setIsxControlCapability(capability);
        coordinator.setControlOver(controlOver);
        return coordinator;
    }

    private static FakeIncusDaemon coordinatorSource() {
        return new FakeIncusDaemon().container("tpl-coord",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-coord"));
    }

    /** Branch {@code name} from {@code source} without starting it; its config as the copy left it. */
    private static Map<String, String> branch(FakeIncusDaemon daemon, String source, String name,
                                              NetworkMode network, Boolean mcpClient, Map<String, ImageDef> defs) {
        var request = new BranchFlow.Request(source, name, false, false, network, null, null, null, null,
                List.of(), false, Map.of(), mcpClient);
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, defs));
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
        var config = new java.util.HashMap<String, String>();
        daemon.instance(name).path("config").asMap().forEach((k, v) -> config.put(k, v.asText("")));
        return config;
    }

    @Test
    void aCoordinatorTemplatesBranchesEachGetAGrantOfTheirOwn() throws Exception {
        var defs = Map.of("tpl-coord", ImageDef.parseYaml("""
                name: tpl-coord
                coordinator:
                  control-over: [tpl-dev]
                """));
        var daemon = coordinatorSource();
        var first = branchLeftToDefaults(daemon, "coord-1", defs);
        var second = branchLeftToDefaults(daemon, "coord-2", defs);
        assertTrue(Metadata.isMcpCaller(first), first::toString);
        assertTrue(Metadata.isMcpCaller(second), second::toString);
        assertNotEquals(first.get(Metadata.MCP_CALLER), second.get(Metadata.MCP_CALLER));
    }

    /** As {@code isx branch <name> --from tpl-coord}: nothing about MCP control in the request. */
    private static Map<String, String> branchLeftToDefaults(FakeIncusDaemon daemon, String name, Map<String, ImageDef> defs) {
        return branch(daemon, "tpl-coord", name, NetworkMode.FULL, null, defs);
    }

    @Test
    void noMcpClientOverridesTheTemplatesDefault() {
        var config = branch(coordinatorSource(), "tpl-coord", "plain", NetworkMode.FULL, false, coordinatorTemplate(true));
        assertFalse(config.containsKey(Metadata.MCP_CALLER), config::toString);
    }

    @Test
    void aTemplateThatSaysNothingMakesNoCoordinator() {
        var config = branch(coordinatorSource(), "tpl-coord", "plain", NetworkMode.FULL, null, coordinatorTemplate(null));
        assertFalse(config.containsKey(Metadata.MCP_CALLER), config::toString);
        var defaults = BranchFlow.defaultsFor("tpl-coord", coordinatorSource().client().instanceMetadata("tpl-coord"), Map.of());
        assertFalse(defaults.mcpClient(), "no definition, no default");
    }

    @Test
    void anAgentsBranchIsNeverACoordinator() {
        // isx mcp's request: no coordinator makes another, whatever the template says
        var daemon = coordinatorSource();
        var defaults = BranchFlow.defaultsFor("tpl-coord", daemon.client().instanceMetadata("tpl-coord"), coordinatorTemplate(true));
        assertTrue(defaults.mcpClient(), "the template asks for MCP control");
        assertFalse(BranchFlow.mcpClient(BranchFlow.Request.defaults("tpl-coord", "agent-1"), defaults));
    }

    @Test
    void aBranchOfAnInstanceIsNoCoordinatorByDefault() {
        // The grant is never inherited, so a copy of a coordinator does not default to being one
        var daemon = new FakeIncusDaemon().container("coord", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.MCP_CALLER, Metadata.newMcpCallerGrant()));
        assertFalse(BranchFlow.defaultsFor("coord", daemon.client().instanceMetadata("coord"), coordinatorTemplate(true)).mcpClient());
    }

    @Test
    void aProjectLocalDefinitionDoesNotMakeACoordinator() {
        var defs = coordinatorTemplate(true);
        defs.get("tpl-coord").setProjectRoot(java.nio.file.Path.of("/home/user/cloned"));
        var daemon = coordinatorSource();
        var recorded = RecordingOutput.during(() -> {
            var config = branch(daemon, "tpl-coord", "coord-1", NetworkMode.FULL, null, defs);
            assertFalse(config.containsKey(Metadata.MCP_CALLER), config::toString);
        });
        assertTrue(recorded.warnings().stream().anyMatch(w -> w.contains("project-local") && w.contains("--mcp-client")),
                () -> String.join("\n", recorded.warnings()));
    }

    @Test
    void anAirgappedBranchOfACoordinatorTemplateIsMadeWithoutTheGrantAndSaysSo() {
        // Not refused: only an explicit --mcp-client is
        var daemon = coordinatorSource();
        var recorded = RecordingOutput.during(() -> {
            var config = branch(daemon, "tpl-coord", "coord-1", NetworkMode.AIRGAP, null, coordinatorTemplate(true));
            assertFalse(config.containsKey(Metadata.MCP_CALLER), config::toString);
        });
        assertTrue(recorded.warnings().stream().anyMatch(w -> w.contains("airgapped") && w.contains("MCP control is off")),
                () -> String.join("\n", recorded.warnings()));
    }

    @Test
    void anExplicitMcpClientIsRefusedWhenAirgapped() {
        var daemon = coordinatorSource();
        assertThrows(BranchFlow.BranchException.class,
                () -> branch(daemon, "tpl-coord", "coord-1", NetworkMode.AIRGAP, true, Map.of()));
        assertEquals(List.of(), daemon.requests());
    }

    @Test
    void theNearestTemplateInTheChainDecides() {
        var parent = coordinatorTemplate(true).get("tpl-coord");
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-coord");
        var grandchild = new ImageDef();
        grandchild.setName("tpl-grandchild");
        grandchild.setParent("tpl-child");
        grandchild.setCoordinator(coordinator(false, null));
        var defs = Map.of("tpl-coord", parent, "tpl-child", child, "tpl-grandchild", grandchild);
        assertTrue(ImageDef.resolveCoordinatorDefault(child, defs), "a child of a coordinator template is one");
        assertFalse(ImageDef.resolveCoordinatorDefault(grandchild, defs), "unless it says otherwise");
        assertNull(ImageDef.resolveControlOver(grandchild, defs), "the layer that decides decides alone");
        assertEquals(List.of(), ImageDef.coordinatorProblems(defs), "opting out of a parent's list is no mistake");
    }

    @Test
    void theCapabilityFollowsTheListUnlessSaid() {
        // Sanne's rule (#1812): on by default exactly when control-over lists something
        java.util.function.Function<ImageDef.Coordinator, Boolean> resolved = coordinator -> {
            var def = new ImageDef();
            def.setName("tpl-x");
            def.setCoordinator(coordinator);
            return ImageDef.resolveCoordinatorDefault(def, Map.of("tpl-x", def));
        };
        assertTrue(resolved.apply(coordinator(null, List.of("tpl-dev"))));
        assertFalse(resolved.apply(coordinator(null, List.of())));
        assertFalse(resolved.apply(coordinator(false, List.of("tpl-dev"))));
        assertTrue(resolved.apply(coordinator(true, List.of())));
        assertFalse(resolved.apply(null));
    }

    @Test
    void turningTheCapabilityOffBesideAListIsWarnedOf() {
        var def = new ImageDef();
        def.setName("tpl-x");
        def.setCoordinator(coordinator(false, List.of("tpl-dev", "tpl-java")));
        assertEquals(List.of("tpl-x: coordinator.isx-control-capability is false, although coordinator.control-over lists"
                + " tpl-dev, tpl-java, so its branches are coordinators only with --mcp-client or the branch dialog's box."
                + " Remove the line to make them coordinators by default, or empty control-over if none should be"),
                ImageDef.coordinatorProblems(Map.of("tpl-x", def)));
        def.setCoordinator(coordinator(false, List.of()));
        assertEquals(List.of(), ImageDef.coordinatorProblems(Map.of("tpl-x", def)), "nothing listed, nothing to warn of");
    }
}
