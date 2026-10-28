package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformTest {

    /**
     * A JVM's {@code os.name} is whatever its command line says, so on Linux it must not make isx
     * take macOS's role, whose proxy identifies a caller by its proof without its address (#1800).
     */
    @Test
    void anOsNameOverrideOnLinuxStaysLinux(@TempDir Path dir) throws Exception {
        var ostype = Files.writeString(dir.resolve("ostype"), "Linux\n");
        var plist = Files.writeString(dir.resolve("SystemVersion.plist"), "<plist/>");
        assertEquals("linux", Platform.osName("Mac OS X", ostype, dir.resolve("absent")));
        assertEquals("linux", Platform.osName("Mac OS X", ostype, plist), "the kernel outweighs a planted plist");
        assertEquals("linux", Platform.osName("Linux", ostype, dir.resolve("absent")));
    }

    @Test
    void macOsIsTakenOnlyWhereMacOsShows(@TempDir Path dir) throws Exception {
        var plist = Files.writeString(dir.resolve("SystemVersion.plist"), "<plist/>");
        var absent = dir.resolve("absent");
        assertEquals("mac os x", Platform.osName("Mac OS X", absent, plist));
        assertEquals("mac os x", Platform.osName("Mac OS X", Files.writeString(dir.resolve("ostype"), "Darwin\n"), plist));
        // A claim of macOS where neither shows: isx takes no role, and does not start
        assertThrows(IllegalStateException.class, () -> Platform.osName("Mac OS X", absent, absent));
        var dirOnly = Files.createDirectory(dir.resolve("dir"));
        assertThrows(IllegalStateException.class, () -> Platform.osName("Mac OS X", absent, dirOnly));
    }

    @Test
    void thisJvmHasTheRoleOfTheKernelItRunsOn() {
        assertEquals(Files.exists(Path.of("/proc/sys/kernel/ostype")), Platform.isLinux());
    }
}
