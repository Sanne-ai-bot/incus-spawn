package dev.incusspawn.graal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlatformRoleFeatureTest {

    @Test
    void aRoleThatIsTheTargetsPasses() {
        assertNull(PlatformRoleFeature.mismatch(false, true, false, true));
        assertNull(PlatformRoleFeature.mismatch(true, false, true, false));
    }

    /** The macOS-role test build's Platform, or -Dos.name given to a Linux builder (#1800). */
    @Test
    void aMacOsRoleInALinuxImageFails() {
        assertNotNull(PlatformRoleFeature.mismatch(false, true, true, false));
        assertNotNull(PlatformRoleFeature.mismatch(false, true, true, true));
        assertNotNull(PlatformRoleFeature.mismatch(true, false, false, true));
    }
}
