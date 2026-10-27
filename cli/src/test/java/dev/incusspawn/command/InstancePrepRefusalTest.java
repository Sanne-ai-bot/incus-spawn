package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How {@code isx shell} and {@code isx run} say why an instance cannot be prepared (#1703). */
class InstancePrepRefusalTest {

    /** One error, its advice under it, which the TUI's log and a recorder keep, never raw stderr lines. */
    @Test
    void aMissingInstanceIsAnErrorWithItsAdvice() {
        var incus = mock(IncusClient.class);
        when(incus.exists("box")).thenReturn(false);
        var recorded = RecordingOutput.during(() -> assertNull(InstancePrep.prepareInstance(incus, "box")));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "no instance named 'box' found.", "Run 'isx list' to see available instances.")),
                recorded.events(RecordingOutput.Kind.ERROR));
    }
}
