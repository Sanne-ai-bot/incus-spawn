package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceNamesTest {

    @Test
    void aShortNameIsKept() {
        assertEquals("dl-file-maven-3", DeviceNames.hotplug("dl-file-maven-3"));
    }

    @Test
    void aLongNameIsCutAndHashed() {
        var a = DeviceNames.hotplug("dl-file-" + "x".repeat(40) + "a");
        var b = DeviceNames.hotplug("dl-file-" + "x".repeat(40) + "b");
        assertTrue(a.length() <= DeviceNames.VM_HOTPLUG_MAX_LENGTH, a);
        assertTrue(a.startsWith("dl-file-xxx"), a);
        assertNotEquals(a, b);
    }

    @Test
    void theSuffixSurvivesTheCut() {
        var name = DeviceNames.hotplug("ref-isx-presentations-11463109", "-3");
        assertEquals(DeviceNames.VM_HOTPLUG_MAX_LENGTH, name.length(), name);
        assertTrue(name.endsWith("-3"), name);
    }
}
