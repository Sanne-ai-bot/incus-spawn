package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
    void aCurrentParentIsNotTouched() {
        var stamp = CertificateAuthority.collectTrustAnchors().stamp();
        var daemon = new FakeIncusDaemon().container("child", Map.of(Metadata.CA_FINGERPRINT, stamp));
        var incus = spy(daemon.client());

        assertFalse(BuildCommand.refreshTrustAnchors(incus, "child"));
        verify(incus, never()).shellExec(eq("child"), any(String[].class));
    }
}
