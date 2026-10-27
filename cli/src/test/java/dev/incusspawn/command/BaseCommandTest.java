package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseCommandTest {

    @Test
    void destructiveConfirmationRefusesWithoutATerminal() {
        // Piped stdin, cron, a wrapper script: never a silent yes for something that wipes data.
        var e = assertThrows(BaseCommand.NoTerminalException.class,
                () -> BaseCommand.confirmDestructive("Wipe?", false, "--yes", null));
        assertTrue(e.getMessage().contains("--yes"), e.getMessage());
        assertTrue(BaseCommand.confirmDestructive("Wipe?", true, "--yes", null), "the skip flag still skips the question");
    }

    /** A command that fails says why as an error, never "Error: null" for an exception without a message. */
    @Test
    void aFailedCommandIsAnErrorThatSaysWhat() throws Exception {
        var failing = new BaseCommand() {
            @Override
            protected org.aesh.command.CommandResult doExecute() {
                throw new IllegalStateException();
            }
        };
        var recorded = dev.incusspawn.util.RecordingOutput.during(() -> assertEquals(1, failing.execute(null).getResultValue()));

        assertEquals(java.util.List.of("java.lang.IllegalStateException"),
                recorded.events(dev.incusspawn.util.RecordingOutput.Kind.ERROR).stream()
                        .map(dev.incusspawn.util.RecordingOutput.Event::msg).toList());
    }

    @Test
    void parseConfirmationAcceptsYesAndNoCaseInsensitively() {
        assertEquals(true, BaseCommand.parseConfirmation(" y ", false));
        assertEquals(true, BaseCommand.parseConfirmation("Y", false));
        assertEquals(false, BaseCommand.parseConfirmation(" n ", true));
        assertEquals(false, BaseCommand.parseConfirmation("N", true));
    }

    @Test
    void parseConfirmationUsesDefaultForBlankResponses() {
        assertEquals(true, BaseCommand.parseConfirmation("", true));
        assertEquals(false, BaseCommand.parseConfirmation("   ", false));
        assertNull(BaseCommand.parseConfirmation(null, true));
        assertNull(BaseCommand.parseConfirmation(null, false));
    }

    /** A yes/no question is shown through the prompts that read it, and asked again until it is answered. */
    @Test
    void askYesNoShowsItsQuestionAndAsksAgain() {
        var prompts = ScriptedPrompts.lines("maybe", "y");
        assertTrue(BaseCommand.askYesNo(prompts, "Install services?", false));
        assertEquals(java.util.List.of("? Install services? (y/N): ", "Please answer y or n.", "? Install services? (y/N): "),
                prompts.conversation());
        prompts.assertFullyConsumed();

        // End of input answers what the caller chose, not the default
        assertTrue(BaseCommand.askYesNo(ScriptedPrompts.lines(), "Keep it?", false, true));
    }

    @Test
    void parseConfirmationRejectsInvalidResponses() {
        assertNull(BaseCommand.parseConfirmation("maybe", true));
    }
}
