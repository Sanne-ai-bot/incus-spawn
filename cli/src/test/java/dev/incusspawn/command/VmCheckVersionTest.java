package dev.incusspawn.command;

import dev.incusspawn.vm.VmManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** #1391: {@code isx vm check-version} says what it found, whatever it found. */
class VmCheckVersionTest {

    @Test
    void aStoppedVmIsSaidToBeStopped() {
        assertEquals("The VM is not running: there is no running appliance to check.",
                VmCommand.CheckVersion.verdict(false, null, null));
    }

    @Test
    void aMatchingApplianceIsNamed() {
        assertEquals("The VM is running appliance 0.3.12; a restart would not change it.",
                VmCommand.CheckVersion.verdict(true, "0.3.12", null));
    }

    @Test
    void anUnreadableVersionIsSaidToBeUnreadable() {
        assertEquals("Could not read the running appliance's version.",
                VmCommand.CheckVersion.verdict(true, null, null));
    }

    @Test
    void skewIsReportedWithItsRestartHint() {
        var skew = new VmManager.ApplianceSkew("0.3.11", "0.3.12");
        assertEquals(skew.message(), VmCommand.CheckVersion.verdict(true, "0.3.11", skew));
    }

    /** install.sh runs it with --quiet on every macOS install: no skew, no line. */
    @Test
    void quietPrintsNothingUnlessARestartWouldChangeTheAppliance() {
        assertNull(VmCommand.CheckVersion.line(true, false, null, null));
        assertNull(VmCommand.CheckVersion.line(true, true, "0.3.12", null));
        assertNull(VmCommand.CheckVersion.line(true, true, null, null));
        var skew = new VmManager.ApplianceSkew("0.3.11", "0.3.12");
        assertEquals(skew.message(), VmCommand.CheckVersion.line(true, true, "0.3.11", skew));
    }

    @Test
    void withoutQuietEveryCaseHasItsLine() {
        assertEquals(VmCommand.CheckVersion.verdict(false, null, null), VmCommand.CheckVersion.line(false, false, null, null));
        assertEquals(VmCommand.CheckVersion.verdict(true, "0.3.12", null), VmCommand.CheckVersion.line(false, true, "0.3.12", null));
    }
}
