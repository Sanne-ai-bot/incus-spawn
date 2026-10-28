package dev.incusspawn.tui;

import java.util.ArrayList;
import java.util.List;

/** The TUI with the terminal stood in for: records what it holds when it would start drawing. */
public final class StandInTui extends Tui {
    public final List<String> events = new ArrayList<>();
    public List<String> warningsAtStart;
    /** Thrown from the run loop, which the TUI reports as why it failed. */
    public RuntimeException failure;

    @Override
    void waitForUser() {
        events.add("wait");
    }

    @Override
    void runTuiLoop() {
        events.add("draw");
        warningsAtStart = warningMessages();
        if (failure != null) throw failure;
    }
}
