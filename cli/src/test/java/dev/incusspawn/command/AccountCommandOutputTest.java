package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What {@code isx account set} and {@code unset} report when they are not told what to change (#1703). */
class AccountCommandOutputTest {

    private static List<RecordingOutput.Event> errors(BaseCommand command) {
        var recorded = RecordingOutput.during(() -> assertEquals(1, command.execute(null).getResultValue()));
        return recorded.events(RecordingOutput.Kind.ERROR);
    }

    /** One error with its usage under it, which the TUI's log and a recorder keep, not loose lines on stderr. */
    @Test
    void aSetWithoutASelectionIsAnErrorWithItsUsage() {
        var set = new AccountCommand.Set();
        set.args = List.of("my-box");
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "name an instance and at least one <namespace>=<account>.",
                "Usage: isx account set <instance> <namespace>=<account> ...",
                "Example: isx account set my-box claude=work")), errors(set));
    }

    @Test
    void anUnsetWithoutANamespaceIsAnErrorWithItsUsage() {
        var unset = new AccountCommand.Unset();
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "name an instance and at least one namespace.",
                "Usage: isx account unset <instance> <namespace> ...",
                "Example: isx account unset my-box claude")), errors(unset));
    }
}
