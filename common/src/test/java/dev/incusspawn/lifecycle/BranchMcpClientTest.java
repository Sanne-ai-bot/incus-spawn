package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1811: the {@link Metadata#MCP_CALLER} grant needs a route to the proxy, so an airgapped branch
 * asking for it is refused by {@code BranchFlow} itself, whichever front end built the request.
 */
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
}
