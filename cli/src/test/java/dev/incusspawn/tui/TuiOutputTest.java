package dev.incusspawn.tui;

import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.RecordingOutput.Event;
import dev.incusspawn.util.RecordingOutput.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        var opened = new AtomicBoolean(true);
        var said = RecordingOutput.during(() -> opened.set(tui.run(null)));
        assertEquals(List.of(new Event(Kind.ERROR, "the terminal went away")), said.events());
        assertFalse(opened.get(), "so bare isx exits non-zero (#1785)");
    }
}
