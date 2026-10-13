package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static dev.incusspawn.incus.FakeIncusDaemon.DNSMASQ_EXITED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A refused network write says why Incus refused it. Without the reason, a failed
 * {@code isx init} on CI printed only "Failed to set network config raw.dnsmasq on incusbr0",
 * and nothing in the log could tell a dnsmasq restart from any other failure (#1376).
 */
class NetworkConfigErrorTest {

    @Test
    void aRefusedWriteCarriesIncussReason() {
        var incus = new FakeIncusDaemon().refuseNetworkWrites(DNSMASQ_EXITED).client();

        var e = assertThrows(IncusException.class,
                () -> incus.networkConfigSet("incusbr0", "raw.dnsmasq", "# x"));

        assertTrue(e.getMessage().startsWith("Failed to set network config raw.dnsmasq on incusbr0: "), e.getMessage());
        assertTrue(e.getMessage().contains(DNSMASQ_EXITED), e.getMessage());
    }

    @Test
    void aFailedReadCarriesIncussReason() {
        var incus = new FakeIncusDaemon().withoutNetwork("incusbr0").client();

        var e = assertThrows(IncusException.class, () -> incus.networkConfigGet("incusbr0", "raw.dnsmasq"));

        assertTrue(e.getMessage().startsWith("Failed to get network config raw.dnsmasq from incusbr0: "), e.getMessage());
        assertTrue(e.getMessage().endsWith(": Not Found"), e.getMessage());
    }

    @Test
    void aFailedWholeConfigReadCarriesIncussReason() {
        var incus = new FakeIncusDaemon().withoutNetwork("incusbr0").client();

        var e = assertThrows(IncusException.class, () -> incus.networkConfig("incusbr0"));

        assertEquals("Failed to get network config from incusbr0: Not Found", e.getMessage());
    }
}
