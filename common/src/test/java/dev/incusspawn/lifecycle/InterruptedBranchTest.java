package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A branch is an instance from its copy on (#1036). {@code isx list -q}, completion and the bench
 * cleanups tell templates apart by the {@code base} type, and a copy carries its template's: a
 * branch interrupted before it is configured (Ctrl-C, a failed write) must not pass for a
 * template, or nothing lists it and it leaks.
 */
@ExtendWith(TempHome.class)
class InterruptedBranchTest {

    @Test
    void aBranchInterruptedRightAfterItsCopyIsAlreadyAnInstance() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE))
                // The branch's configuration write fails, as an interruption right after the copy would
                .refuseWritesContaining("limits.memory");
        var request = new BranchFlow.Request("tpl-dev", "dev-1", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());

        assertThrows(RuntimeException.class,
                () -> BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of())));

        assertNotNull(daemon.instance("dev-1"), "precondition: the copy was made");
        assertEquals(Metadata.TYPE_CLONE, daemon.instance("dev-1").path("config").path(Metadata.TYPE).asText(),
                "the copy itself carries the instance type");
        assertEquals(Metadata.TYPE_BASE, daemon.instance("tpl-dev").path("config").path(Metadata.TYPE).asText(),
                "the template keeps its own");
    }

    @Test
    void aCopyNeverCarriesItsSourcesAgentOwnershipEvenBeforeItIsConfigured() {
        // An agent's instance, forked through isx mcp: the copy is listed before configureBranch
        // drops the source's mcp-* keys, and its idempotency key would answer for the source (#1011).
        var daemon = new FakeIncusDaemon()
                .container("mcp-src", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.MCP_OWNER, "alice",
                        Metadata.MCP_SESSION, "1-1", Metadata.MCP_IDEMPOTENCY_KEY, "k1", Metadata.MCP_KEPT, "yes",
                        Metadata.STATIC_IP, "10.166.11.9"))
                .refuseWritesContaining("limits.memory");
        var request = new BranchFlow.Request("mcp-src", "dev-1", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of(Metadata.MCP_SESSION, "2-2", Metadata.MCP_OWNER, "alice"));

        assertThrows(RuntimeException.class,
                () -> BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of())));

        var config = daemon.instance("dev-1").path("config");
        assertFalse(config.has(Metadata.MCP_IDEMPOTENCY_KEY), config.toString());
        assertFalse(config.has(Metadata.MCP_KEPT), config.toString());
        // isx mcp takes an address as the sign a copy was configured: the copy never has its source's.
        assertFalse(config.has(Metadata.STATIC_IP), config.toString());
        assertEquals("2-2", config.path(Metadata.MCP_SESSION).asText(), "what the caller stamps, it keeps");
        assertEquals("k1", daemon.instance("mcp-src").path("config").path(Metadata.MCP_IDEMPOTENCY_KEY).asText());
    }

    @Test
    void aBranchOfAFailedBuildIsUntrustedFromItsCopyEvenIfTheBuildPredatesTheStamp() {
        // #936: a failed build promoted by an older isx carries no taint, and its copy request is the
        // one moment a branch of it must get one, before anything could give it a secret
        var daemon = new FakeIncusDaemon()
                .container("foo-failed-build", Map.of(Metadata.TYPE, Metadata.TYPE_FAILED_BUILD,
                        Metadata.PROFILE, "tpl-isx-agent"))
                .refuseWritesContaining("limits.memory");
        var request = new BranchFlow.Request("foo-failed-build", "dev-1", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());

        assertThrows(RuntimeException.class,
                () -> BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of())));

        assertEquals(Metadata.TYPE_FAILED_BUILD,
                daemon.instance("dev-1").path("config").path(Metadata.UNTRUSTED_LINEAGE).asText());
    }

    @Test
    void aBranchOfAnUntrustedSourceStaysUntrustedAndNoCallerCanUnsetIt() {
        // The source carries the taint; the copy inherits it, and no extra config the caller passes,
        // an empty value (which unsets) included, removes it
        var daemon = new FakeIncusDaemon()
                .container("dev-1", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PROFILE, "tpl-isx-agent",
                        Metadata.UNTRUSTED_LINEAGE, Metadata.TYPE_FAILED_BUILD))
                .refuseWritesContaining("limits.memory");
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of(Metadata.UNTRUSTED_LINEAGE, ""));

        assertThrows(RuntimeException.class,
                () -> BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of())));

        assertEquals(Metadata.TYPE_FAILED_BUILD,
                daemon.instance("dev-2").path("config").path(Metadata.UNTRUSTED_LINEAGE).asText());
    }

    @Test
    void aSourcesParentsNameAloneNeverTaintsACopyForGood() {
        // A template a user named ci-failed-build is no failed build: the registry refuses its
        // branches while the name says so, but no copy request makes that permanent
        var daemon = new FakeIncusDaemon()
                .container("ci-failed-build", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "ci-failed-build"))
                .container("dev-1", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "ci-failed-build",
                        Metadata.PROFILE, "ci-failed-build"))
                .refuseWritesContaining("limits.memory");
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());

        assertThrows(RuntimeException.class,
                () -> BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of())));

        assertFalse(daemon.instance("dev-2").path("config").has(Metadata.UNTRUSTED_LINEAGE));
    }

    /** The taint of dev-2, branched from dev-1, whose parent is {@code parent}, in {@code daemon}. */
    private static String taintOfABranchOfABranchOf(String parent, FakeIncusDaemon daemon) {
        daemon.container("dev-1", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, parent,
                        Metadata.PROFILE, "tpl-isx-agent"))
                .refuseWritesContaining("limits.memory");
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
        assertThrows(RuntimeException.class,
                () -> BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of())));
        return daemon.instance("dev-2").path("config").path(Metadata.UNTRUSTED_LINEAGE).asText("");
    }

    @Test
    void aBranchOfAnOldBranchOfAFailedBuildIsStampedSoItOutlivesTheInstancesBetween() {
        // dev-1 was branched from foo-failed-build before #936: no taint of its own. A branch of it
        // must carry one, or deleting dev-1 and the failed build would leave it admitted
        var daemon = new FakeIncusDaemon().container("foo-failed-build",
                Map.of(Metadata.TYPE, Metadata.TYPE_FAILED_BUILD, Metadata.PROFILE, "tpl-isx-agent"));
        assertEquals("branched from failed-build", taintOfABranchOfABranchOf("foo-failed-build", daemon));
    }

    @Test
    void aBranchOfABranchWhoseFailedBuildIsGoneIsStampedToo() {
        // Gone, it cannot say it was not a failed build: fail closed
        assertEquals("branched from a deleted *-failed-build",
                taintOfABranchOfABranchOf("foo-failed-build", new FakeIncusDaemon()));
    }
}
