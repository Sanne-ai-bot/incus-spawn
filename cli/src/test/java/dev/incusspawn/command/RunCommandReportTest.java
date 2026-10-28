package dev.incusspawn.command;

import dev.incusspawn.tool.ActionResult;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code isx run} says after an action that runs directly, such as opening a URL (#1703). */
class RunCommandReportTest {

    @Test
    void aSucceededActionIsAStep() {
        var succeeded = new boolean[1];
        var recorded = RecordingOutput.during(() -> succeeded[0] = RunCommand.report(ActionResult.ok("Opened http://localhost:8080")));
        assertTrue(succeeded[0]);
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.STEP, "Opened http://localhost:8080")), recorded.events());
    }

    /**
     * What a copy-to-clipboard action shows without a clipboard is its YAML's text, which a
     * project-local tool chooses: it cannot drive the host terminal (an OSC 52 write to the
     * clipboard, a window title). Its tabs stay, since it is shown to be copied by hand.
     */
    @Test
    void aSucceededActionsTextCannotDriveTheTerminal() {
        var recorded = RecordingOutput.during(() -> RunCommand.report(
                ActionResult.ok("Text: \u001b]52;c;cm0gLXJmIH4=\u0007\u001b]0;not isx\u0007\tall:")));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.STEP, "Text:  ]52;c;cm0gLXJmIH4=  ]0;not isx \tall:")),
                recorded.events());
    }

    /** A success with nothing to say prints nothing, not "null". */
    @Test
    void aSucceededActionWithoutAMessageSaysNothing() {
        assertEquals(List.of(), RecordingOutput.during(() -> RunCommand.report(ActionResult.ok(null))).events());
    }

    /** A failed action is an error, and the command fails with it, never a plain line and exit 0. */
    @Test
    void aFailedActionIsAnError() {
        var succeeded = new boolean[] {true};
        var recorded = RecordingOutput.during(() -> succeeded[0] = RunCommand.report(ActionResult.error("No browser to open")));
        assertFalse(succeeded[0]);
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR, "No browser to open")), recorded.events());
    }

    /** A failure that does not say why still says that it failed. */
    @Test
    void aFailedActionWithoutAMessageStillSaysSo() {
        var recorded = RecordingOutput.during(() -> RunCommand.report(ActionResult.error(null)));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR, "the action failed.")), recorded.events());
    }
}
