package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.tool.ToolDefLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * A child build copies its parent's guest, trust anchors included, and is stamped as holding
 * the current set when it finishes: a reconcile that failed must fail the build, or the
 * template reads as current with no proxy CA in it and every branch inherits broken TLS.
 */
@ExtendWith(IsolatedHome.class)
class BuildCommandTrustAnchorsTest {

    @Test
    void aFailedRefreshFailsTheBuild() {
        var daemon = new FakeIncusDaemon().container("child", Map.of(Metadata.CA_FINGERPRINT, "deadbeef"));
        var incus = spy(daemon.client());
        doReturn(new IncusClient.ExecResult(1, "", "No space left on device"))
                .when(incus).shellExec(eq("child"), any(String[].class));

        var e = assertThrows(IncusException.class, () -> BuildCommand.refreshTrustAnchors(incus, "child"));
        assertTrue(e.getMessage().contains("No space left on device"), e.getMessage());
    }

    @Test
    void theTemplateIsStampedWithTheSetItHolds() throws Exception {
        // Stamped from a fresh read at the end of the build, a change in between (the CA, a
        // host anchor, propagation turned off) would be recorded as installed: the template and
        // its branches would read as current and never get, or revoke, what they hold
        var daemon = new FakeIncusDaemon().container("child", Map.of(Metadata.CA_FINGERPRINT, "deadbeef"));
        var incus = spy(daemon.client());
        doReturn(new IncusClient.ExecResult(0, "", "")).when(incus).shellExec(eq("child"), any(String[].class));
        assertTrue(BuildCommand.refreshTrustAnchors(incus, "child"));
        var installed = CertificateAuthority.collectTrustAnchors().stamp();

        Files.delete(SpawnConfig.configDir().resolve("ca.crt"));
        Files.delete(SpawnConfig.configDir().resolve("ca.key"));
        assertNotEquals(installed, CertificateAuthority.collectTrustAnchors().stamp(), "sanity: the CA rotated");

        var cmd = new BuildCommand();
        cmd.incus = incus;
        cmd.toolDefLoader = mock(ToolDefLoader.class);
        cmd.stampBuildVersion("child", new ImageDef(), Map.of());

        assertEquals(installed, incus.configGet("child", Metadata.CA_FINGERPRINT));
    }

    @Test
    void aCurrentParentIsNotTouched() {
        var stamp = CertificateAuthority.collectTrustAnchors().stamp();
        var daemon = new FakeIncusDaemon().container("child", Map.of(Metadata.CA_FINGERPRINT, stamp));
        var incus = spy(daemon.client());

        assertFalse(BuildCommand.refreshTrustAnchors(incus, "child"));
        verify(incus, never()).shellExec(eq("child"), any(String[].class));
    }
}
