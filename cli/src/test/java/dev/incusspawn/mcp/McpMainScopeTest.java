package dev.incusspawn.mcp;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tui.InstanceLockManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * #1812: the scope {@code isx mcp} gives a session, through the wiring {@code McpMain.run} uses:
 * a coordinator's lineage read from the listing, then its template's own list.
 */
class McpMainScopeTest {

    private static final Map<String, ImageDef> DEFS = Map.of("tpl-coord", coordinatorTemplate());

    private static ImageDef coordinatorTemplate() {
        var def = new ImageDef();
        def.setName("tpl-coord");
        var coordinator = new ImageDef.Coordinator();
        coordinator.setControlOver(List.of("tpl-a", "tpl-c"));
        def.setCoordinator(coordinator);
        return def;
    }

    private final IncusInstanceBackend backend = new IncusInstanceBackend(new FakeIncusDaemon()
            .container("tpl-coord", Map.of(Metadata.TYPE, Metadata.TYPE_BASE))
            .container("coord", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PROFILE, "tpl-coord",
                    Metadata.PARENT, "tpl-coord", Metadata.MCP_CALLER, Metadata.newMcpCallerGrant()))
            .client(), mock(InstanceLockManager.class));

    private final McpConfig config = new McpConfig();

    McpMainScopeTest() {
        config.setTemplates(List.of("tpl-a", "tpl-b"));
    }

    @Test
    void aCoordinatorGetsWhatBothListsHold() {
        assertEquals(List.of("tpl-a"), McpMain.scopeFor(backend, "coord", () -> DEFS).apply(config).templates());
    }

    @Test
    void aCallerTheListingDoesNotHoldGetsNothing() {
        assertEquals(List.of(), McpMain.scopeFor(backend, "gone", () -> DEFS).apply(config).templates());
    }

    @Test
    void theHostsOwnSessionKeepsMcpYamlsList() {
        assertEquals(List.of("tpl-a", "tpl-b"), McpMain.scopeFor(backend, null, () -> DEFS).apply(config).templates());
    }
}
