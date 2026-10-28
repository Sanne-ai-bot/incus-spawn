package dev.incusspawn.tui;

import java.util.ArrayList;
import java.util.List;

/** The TUI with the terminal stood in for: records what it holds when it would start drawing. */
public final class StandInTui extends Tui {
    public final List<String> events = new ArrayList<>();
    public List<String> warningsAtStart;
    /** Thrown from the run loop, which the TUI reports as why it failed. */
    public RuntimeException failure;
    /** Thrown where the TUI loads its services, after the warnings were handed over. */
    public RuntimeException servicesFailure;

    @Override
    void waitForUser() {
        events.add("wait");
    }

    @Override
    void loadServices() {
        if (servicesFailure != null) throw servicesFailure;
        super.loadServices();
    }

    @Override
    void runTuiLoop() {
        events.add("draw");
        warningsAtStart = warningMessages();
        if (failure != null) throw failure;
    }
}
