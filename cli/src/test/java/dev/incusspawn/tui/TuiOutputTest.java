package dev.incusspawn.tui;

import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.RecordingOutput.Event;
import dev.incusspawn.util.RecordingOutput.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the TUI says on the terminal it does not draw on goes through the bound output (#1703). */
// open() loads RuntimeServices: never against the developer's home.
@ExtendWith(IsolatedHome.class)
class TuiOutputTest {

    @Test
    void aFailureOpeningTheTuiIsAnError() {
        var tui = new Tui() {
            @Override
            void runTuiLoop() {
                throw new IllegalStateException("the terminal went away");
            }
        };
        var said = RecordingOutput.during(() -> tui.run(null));
        assertEquals(List.of(new Event(Kind.ERROR, "the terminal went away")), said.events());
    }
}
