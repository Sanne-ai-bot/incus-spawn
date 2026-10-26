package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Starting an instance whose IP spoofing protection the host cannot enforce.
 *
 * <p>Found by running against a real Incus: setting {@code security.ipv4_filtering} succeeds
 * even where it cannot be enforced, and the failure only appears when the instance starts and
 * Incus installs the per-NIC rules. Without the fallback, such a host would accept every branch
 * and then be unable to start any of them.
 */
class IpFilteringFallbackTest {

    private static final String NAME = "box";

    /** The message a host without the ebtables filter table actually produces. */
    private static final String EBTABLES_FAILURE =
            "Operation failed: Failed to start device \"eth0\": Failed to run: "
                    + "ebtables -t filter -A INPUT -s ! 10:66:6a:f9:3d:b3 -i veth0 -j DROP: "
                    + "exit status 255 (The kernel doesn't support the ebtables 'filter' table.)";

    /**
     * What Incus 6.21's nftables driver says on the macOS appliance before its kernel had the nft
     * bridge family (#905), recorded from a real start (repeated per-chain lines trimmed).
     */
    private static final String NFT_BRIDGE_FAILURE =
            "Failed to start device \"eth0\": Failed adding bridge filter rules for instance device "
                    + "\"a.eth0\" (bridge): Failed apply nftables config: Failed to run: nft -f -: "
                    + "exit status 1 (/dev/stdin:2:1-2: Error: Could not process rule: Not supported\n"
                    + "table bridge incus {\n^^\n"
                    + "/dev/stdin:2:14-18: Error: Could not process rule: No such file or directory\n"
                    + "table bridge incus {\n             ^^^^^)";

    private static IncusClient withFilteringOn() {
        var incus = mock(IncusClient.class);
        when(incus.findNic(eq(NAME), anyString()))
                .thenReturn(new IncusClient.NicDevice("eth0",
                        Map.of("security.ipv4_filtering", "true")));
        return incus;
    }

    @ParameterizedTest
    @ValueSource(strings = {EBTABLES_FAILURE, NFT_BRIDGE_FAILURE})
    void startsOnceFilteringIsDroppedOnAHostThatCannotEnforceIt(String failure) {
        var incus = withFilteringOn();
        doThrow(new IncusException(failure)).doNothing().when(incus).start(NAME);

        InstanceLifecycle.startInstance(incus, NAME);

        verify(incus).deviceConfigSet(NAME, "eth0", "security.ipv4_filtering", "false");
        verify(incus, times(2)).start(NAME);
    }

    @Test
    void theLossOfProtectionIsReportedToTheCallersSink() {
        var incus = withFilteringOn();
        doThrow(new IncusException(NFT_BRIDGE_FAILURE)).doNothing().when(incus).start(NAME);
        var warnings = new ArrayList<String>();

        // The TUI's warning log: stderr would be drawn over while the TUI owns the terminal.
        InstanceLifecycle.startInstance(incus, NAME, RecordingOutput.into(warnings));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("cannot enforce IP spoofing protection, so it has been disabled on box"),
                warnings.get(0));
        assertTrue(warnings.get(0).contains("impersonate each other's credential accounts"), warnings.get(0));
    }

    /**
     * The overload without an output, which build-time starts use, reports to the bound one: what
     * the TUI copies into its log for a build it runs (#1646).
     */
    @Test
    void withoutAnOutputTheLossOfProtectionGoesToTheBoundOne() {
        var incus = withFilteringOn();
        doThrow(new IncusException(NFT_BRIDGE_FAILURE)).doNothing().when(incus).start(NAME);

        var bound = RecordingOutput.during(() -> InstanceLifecycle.startInstance(incus, NAME));

        assertEquals(List.of("this host cannot enforce IP spoofing protection, so it has been disabled"
                + " on box: instances on this host can impersonate each other's credential accounts."), bound.warnings());
    }

    /** To the caller's output, the loss of protection is one typed warning, never a progress line (#1703). */
    @Test
    void theLossOfProtectionIsOneWarningOnTheCallersOutput() {
        var incus = withFilteringOn();
        doThrow(new IncusException(NFT_BRIDGE_FAILURE)).doNothing().when(incus).start(NAME);
        var out = new RecordingOutput();

        InstanceLifecycle.startInstance(incus, NAME, out);

        assertEquals(out.lines(), out.warnings(), "nothing but the warning");
        assertEquals(1, out.warnings().size(), out.warnings().toString());
        assertTrue(out.warnings().getFirst().startsWith("this host cannot enforce IP spoofing protection"),
                out.warnings().getFirst());
        assertFalse(out.warnings().getFirst().contains("\n"), out.warnings().getFirst());
    }

    /**
     * The fallback strips a security setting, so it must not trigger on anything else -- a
     * disk, a GPU, an out-of-memory start failure. Those have to surface as themselves.
     */
    @Test
    void anUnrelatedStartFailureIsNotSilentlyTurnedIntoALossOfProtection() {
        var incus = withFilteringOn();
        doThrow(new IncusException("Failed to start device \"gpu\": no such device"))
                .when(incus).start(NAME);

        assertThrows(IncusException.class, () -> InstanceLifecycle.startInstance(incus, NAME));

        verify(incus, never()).deviceConfigSet(any(), any(), eq("security.ipv4_filtering"), any());
        verify(incus, times(1)).start(NAME);
    }

    @Test
    void aStartFailureWithFilteringAlreadyOffIsNotRetried() {
        var incus = mock(IncusClient.class);
        when(incus.findNic(eq(NAME), anyString()))
                .thenReturn(new IncusClient.NicDevice("eth0", Map.of()));
        doThrow(new IncusException(EBTABLES_FAILURE)).when(incus).start(NAME);

        assertThrows(IncusException.class, () -> InstanceLifecycle.startInstance(incus, NAME));
        verify(incus, times(1)).start(NAME);
    }

    @Test
    void aNormalStartDoesNotTouchTheNic() {
        var incus = mock(IncusClient.class);
        InstanceLifecycle.startInstance(incus, NAME);

        verify(incus).start(NAME);
        verify(incus, never()).findNic(any(), any());
    }

    /** Only a template starts without {@link InstanceLifecycle#startForUse}'s repairs and secret (#1457). */
    @Test
    void startTemplateRefusesAnInstance() {
        var incus = mock(IncusClient.class);
        var instance = JqObject.builder(1)
                .put("config", JqObject.builder(1)
                        .put(Metadata.TYPE, Metadata.TYPE_CLONE).build()).build();

        assertThrows(IllegalArgumentException.class,
                () -> InstanceLifecycle.startTemplate(incus, NAME, instance, RecordingOutput.QUIET));
        verify(incus, never()).start(any());
    }

    @Test
    void recognisesTheFilteringFailureAndOnlyThat() {
        assertTrue(InstanceNetwork.looksLikeIpFilteringFailure(
                new IncusException(EBTABLES_FAILURE)));
        assertTrue(InstanceNetwork.looksLikeIpFilteringFailure(
                new IncusException(NFT_BRIDGE_FAILURE)));
        // Nested, as Incus exceptions often arrive.
        assertTrue(InstanceNetwork.looksLikeIpFilteringFailure(
                new RuntimeException("wrapped", new IncusException(EBTABLES_FAILURE))));
        assertFalse(InstanceNetwork.looksLikeIpFilteringFailure(
                new IncusException("Failed to start device \"root\": disk full")));
        assertFalse(InstanceNetwork.looksLikeIpFilteringFailure(
                new IncusException("ebtables is unhappy but no device is named")));
        assertFalse(InstanceNetwork.looksLikeIpFilteringFailure(new IncusException(null)));
    }
}
