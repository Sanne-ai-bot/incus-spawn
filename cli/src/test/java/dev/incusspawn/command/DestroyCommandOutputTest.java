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

    /** A bulk destroy that left something behind, which fails the command, ends on an error. */
    @Test
    void aBulkDestroyThatLeftSomethingBehindEndsOnAnError() {
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.SUCCESS, "Destroyed 2 instance(s)")),
                summarized(new DestroyCommand.DestroyResult(2, 0, 0)));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR, "Destroyed 1 instance(s). 1 skipped (locked).")),
                summarized(new DestroyCommand.DestroyResult(1, 1, 0)));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR, "Destroyed 1 instance(s). 1 skipped (locked). 1 failed.")),
                summarized(new DestroyCommand.DestroyResult(1, 1, 1)));
    }

    private static List<RecordingOutput.Event> summarized(DestroyCommand.DestroyResult result) {
        return RecordingOutput.during(() -> DestroyCommand.summarize(result, "instance")).events();
    }
}
