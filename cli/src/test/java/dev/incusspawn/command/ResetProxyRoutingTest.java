package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code isx reset} fails, saying so, whenever what routes instances to the proxy may stay, as
 * {@code isx proxy uninstall} does: an Incus it cannot reach is no reason to skip the bridge DNS
 * overrides unseen.
 */
class ResetProxyRoutingTest {

    private final List<String> tried = new ArrayList<>();

    @Test
    void anUnreachableIncusFailsTheResetWithAWarning() {
        var recorded = RecordingOutput.during(() -> assertFalse(ResetCommand.clearProxyRouting(false, false,
                () -> tried.add("dns"), () -> tried.add("redirects"))));

        assertEquals(List.of("redirects"), tried, "the redirect rules are cleared, Incus is not asked");
        assertEquals(1, recorded.warnings().size(), recorded.events()::toString);
        assertTrue(recorded.warnings().getFirst().startsWith("Could not clear the bridge DNS overrides: Incus is not reachable."),
                recorded.warnings()::toString);
    }

    /**
     * On macOS with the VM stopped, Incus is not reachable, but the overrides live on the VM's
     * data disk, which the reset deletes: that is no failure, unless the disk stays.
     */
    @Test
    void overridesOnADataDiskTheResetDeletesGoWithIt(@TempDir Path dir) throws Exception {
        var recorded = RecordingOutput.during(() -> assertTrue(ResetCommand.clearProxyRouting(false, true,
                () -> tried.add("dns"), () -> tried.add("redirects"))));
        assertEquals(List.of("redirects"), tried, "Incus is not asked; the redirect rules are cleared");
        assertEquals(List.of(), recorded.warnings());

        var disk = dir.resolve("data.img");
        var deleted = RecordingOutput.during(() -> assertFalse(ResetCommand.overridesStayOn(disk)));
        assertEquals(List.of(), deleted.warnings());

        Files.writeString(disk, "");
        var stayed = RecordingOutput.during(() -> assertTrue(ResetCommand.overridesStayOn(disk)));
        assertEquals(1, stayed.warnings().size(), stayed.events()::toString);
        assertTrue(stayed.warnings().getFirst().contains(disk.toString()), stayed.warnings()::toString);
    }

    @Test
    void overridesIncusRefusedToClearFailTheReset() {
        assertFalse(ResetCommand.clearProxyRouting(true, false, () -> false, () -> tried.add("redirects")));
        assertEquals(List.of("redirects"), tried);
        assertFalse(ResetCommand.clearProxyRouting(true, false, () -> true, () -> false));
    }

    @Test
    void bothClearedIsASuccess() {
        var recorded = RecordingOutput.during(() -> assertTrue(ResetCommand.clearProxyRouting(true, false, () -> true, () -> true)));
        assertEquals(List.of(), recorded.warnings());
    }
}
