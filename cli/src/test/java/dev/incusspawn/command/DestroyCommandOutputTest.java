package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@code isx destroy} reports when its arguments name nothing it can act on (#1703). */
class DestroyCommandOutputTest {

    private static List<String> destroy(String name, boolean allTemplates, boolean allInstances) throws Exception {
        var command = new DestroyCommand();
        command.name = name;
        command.allTemplates = allTemplates;
        command.allInstances = allInstances;
        var recorded = RecordingOutput.during(() -> assertEquals(1, command.execute(null).getResultValue()));
        return recorded.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList();
    }

    /** Each refusal is one error, which the TUI's log and a recorder keep, not a raw "Error:" line on stderr. */
    @Test
    void conflictingArgumentsAreAnError() throws Exception {
        assertEquals(List.of("specify --all-templates or --all-instances, not both."),
                destroy(null, true, true));
        assertEquals(List.of("cannot combine a named target with --all-templates / --all-instances."),
                destroy("box", true, false));
        assertEquals(List.of("specify a name, or use --all-templates / --all-instances."),
                destroy(null, false, false));
    }
}
