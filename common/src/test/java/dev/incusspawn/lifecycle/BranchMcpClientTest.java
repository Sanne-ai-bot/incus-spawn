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
 * #1812: a template's {@code mcp-client.default} makes its branches coordinators, each with a
 * grant of its own, unless the request says otherwise.
 */
@ExtendWith({TempHome.class, LinuxBranching.class})
class BranchMcpClientTest {

    private static BranchFlow.Request request(NetworkMode network) {
        return request(network, Metadata.newMcpCallerGrant());
    }

    private static BranchFlow.Request request(NetworkMode network, String grant) {
        return new BranchFlow.Request("tpl-dev", "coord", null, null, network, null, null, null, null,
                List.of(), false, Map.of(Metadata.MCP_CALLER, grant));
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
    void anyValueOfTheStampIsRefusedWhenAirgapped() {
        // Not a well-formed grant, so it would grant nothing; still, no airgapped branch is stamped
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of());
        assertThrows(BranchFlow.BranchException.class,
                () -> BranchFlow.preflight(daemon.client(), request(NetworkMode.AIRGAP, "not-a-grant"), Map.of()));
        assertEquals(List.of(), daemon.requests());
    }

    @Test
    void onlyAirgappedIsUnreachable() {
        assertNull(BranchFlow.mcpClientUnreachable(NetworkMode.FULL));
        assertNull(BranchFlow.mcpClientUnreachable(NetworkMode.PROXY_ONLY));
        assertNotNull(BranchFlow.mcpClientUnreachable(NetworkMode.AIRGAP));
    }

    private static Map<String, ImageDef> coordinatorTemplate(Boolean byDefault) {
        var def = new ImageDef();
        def.setName("tpl-coord");
        var mcpClient = new ImageDef.McpClient();
        mcpClient.setByDefault(byDefault);
        def.setMcpClient(mcpClient);
        return Map.of("tpl-coord", def);
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
                mcp-client:
                  default: true
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
        var off = new ImageDef.McpClient();
        off.setByDefault(false);
        grandchild.setMcpClient(off);
        var defs = Map.of("tpl-coord", parent, "tpl-child", child, "tpl-grandchild", grandchild);
        assertTrue(ImageDef.resolveMcpClientDefault(child, defs), "a child of a coordinator template is one");
        assertFalse(ImageDef.resolveMcpClientDefault(grandchild, defs), "unless it says otherwise");
    }
}
