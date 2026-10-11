package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code --proxy-only} fails closed when its firewall cannot be put in place (#1136). */
@ExtendWith(TempHome.class)
class ProxyOnlyFirewallTest {

    private final IncusClient incus = mock(IncusClient.class);

    @Test
    void anImageWithoutIptablesFailsTheBranchAndLosesTheInstance() {
        iptablesFails(127, "sh: line 1: iptables: command not found");

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        assertTrue(e.getMessage().contains("iptables: command not found"), e.getMessage());
        assertTrue(e.getMessage().contains("iptables-nft"), e.getMessage());
        verify(incus).delete("box", true);
        assertEquals(1, TempHome.proxySignals(), "the proxy forgets the freed address");
    }

    @Test
    void aGatewayIncusCannotReadFailsClosed() {
        when(incus.configGet("box", Metadata.PROXY_GATEWAY)).thenThrow(new IncusException("HTTP 500"));

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
        verify(incus, never()).shellExec(anyString(), any(String[].class));
        verify(incus).delete("box", true);
    }

    @Test
    void noRecordedGatewayFailsTheBranchRatherThanSkippingTheRules() {
        when(incus.configGet("box", Metadata.PROXY_GATEWAY)).thenReturn("");

        assertThrows(BranchFlow.BranchException.class, () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        verify(incus, never()).shellExec(anyString(), any(String[].class));
        verify(incus).delete("box", true);
    }

    @Test
    void anInstanceThatCannotBeRemovedIsStopped() {
        iptablesFails(3, "can't initialize iptables table `filter'");
        doThrow(new IncusException("storage busy")).when(incus).delete("box", true);
        stillThere();

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        verify(incus).forceStop("box");
        assertTrue(e.getMessage().contains("was stopped instead"), e.getMessage());
        assertTrue(e.getMessage().contains("isx destroy box"), e.getMessage());
    }

    @Test
    void anInstanceThatCanBeNeitherRemovedNorStoppedSaysSoLoudly() {
        iptablesFails(3, "can't initialize iptables table `filter'");
        doThrow(new IncusException("storage busy")).when(incus).delete("box", true);
        stillThere();
        doThrow(new IncusException("daemon gone")).when(incus).forceStop("box");

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        assertTrue(e.getMessage().contains("HAS UNRESTRICTED NETWORK ACCESS"), e.getMessage());
    }

    @Test
    void anInstanceIncusCannotAnswerForAfterAFailedDeleteIsStoppedNotCalledGone() {
        iptablesFails(127, "sh: line 1: iptables: command not found");
        doThrow(new IncusException("storage busy")).when(incus).delete("box", true);
        when(incus.instanceMetadataOrThrow("box")).thenThrow(new IncusException("Failed to read instance 'box' (HTTP 500)"));

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        verify(incus).forceStop("box");
        assertTrue(e.getMessage().contains("was stopped instead"), e.getMessage());
    }

    @Test
    void aDeleteThatFailedAfterIncusDeletedTheInstanceCountsAsRemoved() {
        iptablesFails(127, "sh: line 1: iptables: command not found");
        doThrow(new IncusException("websocket closed")).when(incus).delete("box", true);
        when(incus.instanceMetadataOrThrow("box")).thenReturn(null);

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        verify(incus, never()).forceStop("box");
        assertTrue(e.getMessage().contains("was removed"), e.getMessage());
    }

    @Test
    void aStopRefusedBecauseTheFailedDeleteAlreadyStoppedItIsNotCalledUnrestricted() {
        iptablesFails(127, "sh: line 1: iptables: command not found");
        doThrow(new IncusException("storage busy")).when(incus).delete("box", true);
        stillThere();
        doThrow(new IncusException("The instance is already stopped")).when(incus).forceStop("box");
        when(incus.getInstanceStatus("box")).thenReturn("Stopped");

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        assertTrue(e.getMessage().contains("was stopped instead"), e.getMessage());
        assertFalse(e.getMessage().contains("UNRESTRICTED"), e.getMessage());
    }

    @Test
    void anExecIncusCannotRunFailsClosedToo() {
        when(incus.configGet("box", Metadata.PROXY_GATEWAY)).thenReturn("10.97.155.1");
        when(incus.shellExec(anyString(), any(String[].class))).thenThrow(new IncusException("websocket closed"));

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> RuntimeSetup.applyProxyOnlyFirewall(incus, "box"));

        assertTrue(e.getMessage().contains("websocket closed"), e.getMessage());
        verify(incus).delete("box", true);
    }

    private void stillThere() {
        when(incus.instanceMetadataOrThrow("box")).thenReturn(JsonNodeFactory.instance.objectNode());
    }

    private void iptablesFails(int exitCode, String stderr) {
        when(incus.configGet("box", Metadata.PROXY_GATEWAY)).thenReturn("10.97.155.1");
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(new IncusClient.ExecResult(exitCode, "", stderr));
    }
}
