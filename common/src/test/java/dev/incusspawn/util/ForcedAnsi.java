package dev.incusspawn.util;

/**
 * Forces {@link BuildOutput#ansi()} for a test outside this package, which cannot reach
 * {@code BuildOutput.forceAnsi}: {@code try (var ansi = ForcedAnsi.on()) { ... }}.
 */
public final class ForcedAnsi implements AutoCloseable {

    private final Boolean previous;

    private ForcedAnsi(Boolean value) {
        previous = BuildOutput.forceAnsi;
        BuildOutput.forceAnsi = value;
    }

    /** ANSI on until closed, whatever the console says. */
    public static ForcedAnsi on() {
        return new ForcedAnsi(true);
    }

    @Override
    public void close() {
        BuildOutput.forceAnsi = previous;
    }
}
