package dev.incusspawn.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * An {@link Output} for tests: what code reported through it, in order, without a terminal or a
 * swapped stream. {@link #lines()} is every progress line, note and warning as its text, the way
 * a {@code Consumer<String>} sink saw them; {@link #warnings()} is the warnings alone.
 */
public final class RecordingOutput implements Output {

    /** Reports nowhere: for a test that does not look at what was said. */
    public static final Output QUIET = (msg, details) -> {};

    /** Fails the test on any warning, error or banner: for code that must have nothing to warn of. */
    public static final Output FAIL_ON_WARN = (msg, details) -> fail("unexpected warning: " + msg);

    private final List<String> lines = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @Override
    public synchronized void warn(String msg, String... details) {
        var text = text(msg, details);
        if (text == null) return;
        warnings.add(text);
        lines.add(text);
    }

    @Override
    public synchronized void step(String msg) {
        lines.add(msg);
    }

    @Override
    public synchronized void stepStart(String msg) {
        lines.add(msg);
    }

    @Override
    public synchronized void note(String msg) {
        lines.add(msg);
    }

    @Override
    public synchronized void stepNote(String msg) {
        lines.add(msg);
    }

    @Override
    public synchronized void ok(String msg) {
        lines.add(msg);
    }

    @Override
    public synchronized void success(String msg) {
        lines.add(msg);
    }

    /**
     * An output that adds every progress line, note and warning to {@code lines}, as the list's
     * {@code add} did as a {@code Consumer<String>} sink: for a test that predates outputs.
     */
    public static Output into(List<String> lines) {
        return new Output() {
            @Override
            public void warn(String msg, String... details) {
                var text = text(msg, details);
                if (text != null) lines.add(text);
            }

            @Override
            public void step(String msg) {
                lines.add(msg);
            }

            @Override
            public void stepStart(String msg) {
                lines.add(msg);
            }

            @Override
            public void note(String msg) {
                lines.add(msg);
            }
        };
    }

    /** Every progress line, note and warning, in the order reported. */
    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    /** The warnings, each with its details on lines under it. */
    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** A warning and its details as one text; null when blank, which no output reports. */
    private static String text(String msg, String... details) {
        if (msg == null || msg.isBlank()) return null;
        return String.join("\n", Stream.concat(Stream.of(msg), Stream.of(details)).toList());
    }
}
