package dev.incusspawn.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The recording binding (#1703): an {@link Output} that keeps what code reported as typed
 * {@link Event}s, in order, so a test asserts on what was said and of which kind, never on bytes
 * a swapped {@code System.out} caught. Bind it ({@link BuildOutput#bind}) for code that reports
 * through {@link BuildOutput} or {@code Warnings}, or hand it to code that takes an {@link Output}.
 *
 * <p>It overrides every method of {@link Output} ({@code OutputBindingTest}), so nothing falls
 * through to a default that would drop it unrecorded.
 */
public final class RecordingOutput implements Output {

    /** Reports nowhere: for a test that does not look at what was said. */
    public static final Output QUIET = (msg, details) -> {};

    /** Fails the test on any warning, error or banner: for code that must have nothing to warn of. */
    public static final Output FAIL_ON_WARN = (msg, details) -> fail("unexpected warning: " + msg);

    /** What kind of message an event is: one per method of {@link Output} that reports. */
    public enum Kind {
        WARN, WARN_ASIDE, STEP_WARN, WARN_BANNER, STEP_FAIL, NOTE,
        HEADER, BUILD_HEADER, BRANCH_HEADER, SECTION, GROUP, GROUP_END,
        STEP, STEP_NOTE, OK, SUCCESS, LIST,
        STEP_START, STEP_PROGRESS, STEP_DONE, STEP_BREAK, RELEASE_TERMINAL, ABANDON_STEP;

        /** The kinds {@link Output} treats as diagnostics: those that default to {@link Output#warn}. */
        public static final Set<Kind> DIAGNOSTICS = EnumSet.of(WARN, WARN_ASIDE, STEP_WARN, WARN_BANNER, STEP_FAIL);
    }

    /** One message: its kind, its text (null for a kind that has none) and the lines under it. */
    public record Event(Kind kind, String msg, List<String> details) {
        Event(Kind kind, String msg, String... details) {
            this(kind, msg, List.of(details));
        }

        /** The message and its details as one text, the way a log keeps a warning. */
        public String text() {
            return details.isEmpty() ? msg : msg + "\n" + String.join("\n", details);
        }
    }

    private final List<Event> events = new ArrayList<>();
    /** Where {@link #into} also adds each line; null for a recorder read through its methods. */
    private final List<String> mirror;

    public RecordingOutput() {
        this(null);
    }

    private RecordingOutput(List<String> mirror) {
        this.mirror = mirror;
    }

    /**
     * A recorder that also adds each line ({@link #lines()}) to {@code lines} as it comes: for a
     * test that collects into a list it already has.
     */
    public static RecordingOutput into(List<String> lines) {
        return new RecordingOutput(lines);
    }

    private synchronized void record(Kind kind, String msg, String... details) {
        // Blank warnings are skipped by every output, so none is recorded either
        if (Kind.DIAGNOSTICS.contains(kind) && (msg == null || msg.isBlank())) return;
        var event = new Event(kind, msg, details);
        events.add(event);
        if (mirror != null && isLine(event)) mirror.add(event.text());
    }

    @Override public void warn(String msg, String... details) { record(Kind.WARN, msg, details); }
    @Override public void warnAside(String msg) { record(Kind.WARN_ASIDE, msg); }
    @Override public void stepWarn(String msg) { record(Kind.STEP_WARN, msg); }
    @Override public void warnBanner(String title, String... lines) { record(Kind.WARN_BANNER, title, lines); }
    @Override public void stepFail(String msg) { record(Kind.STEP_FAIL, msg); }
    @Override public void note(String msg) { record(Kind.NOTE, msg); }
    @Override public void header(String msg, String detail) {
        record(Kind.HEADER, msg, detail == null ? new String[0] : new String[] {detail});
    }
    @Override public void buildHeader(String name, int index, int total) {
        record(Kind.BUILD_HEADER, name, index + "/" + total);
    }
    @Override public void branchHeader(String name, String source) { record(Kind.BRANCH_HEADER, name, source); }
    @Override public void section(String msg) { record(Kind.SECTION, msg); }
    @Override public Group group(String title, String detail) {
        record(Kind.GROUP, title, detail == null ? new String[0] : new String[] {detail});
        return () -> record(Kind.GROUP_END, null);
    }
    @Override public String indent() { return ""; }
    @Override public void step(String msg) { record(Kind.STEP, msg); }
    @Override public void stepNote(String msg) { record(Kind.STEP_NOTE, msg); }
    @Override public void ok(String msg) { record(Kind.OK, msg); }
    @Override public void success(String msg) { record(Kind.SUCCESS, msg); }
    @Override public void list(Collection<String> items) { record(Kind.LIST, String.join(", ", items)); }
    @Override public void stepStart(String msg) { record(Kind.STEP_START, msg); }
    @Override public void stepProgress(String detail) { record(Kind.STEP_PROGRESS, detail); }
    @Override public void stepDone(String detail) { record(Kind.STEP_DONE, detail); }
    @Override public void stepBreak() { record(Kind.STEP_BREAK, null); }
    @Override public void releaseTerminal() { record(Kind.RELEASE_TERMINAL, null); }
    @Override public void abandonStep() { record(Kind.ABANDON_STEP, null); }
    @Override public List<Output> destinations() { return List.of(this); }

    /** Everything reported, in order. */
    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    /** The events of {@code kind}. */
    public synchronized List<Event> events(Kind kind) {
        return events.stream().filter(e -> e.kind() == kind).toList();
    }

    /**
     * Every message a person reads, in order, each with its details: what a {@code Consumer<String>}
     * sink saw. Not a live step's progress or end, which only redraw a line already counted.
     */
    public synchronized List<String> lines() {
        return events.stream().filter(RecordingOutput::isLine).map(Event::text).toList();
    }

    /** The diagnostics ({@link Kind#DIAGNOSTICS}), each with its details on lines under it. */
    public synchronized List<String> warnings() {
        return events.stream().filter(e -> Kind.DIAGNOSTICS.contains(e.kind())).map(Event::text).toList();
    }

    /** The steps, live steps' starts included. */
    public synchronized List<String> steps() {
        return events.stream().filter(e -> e.kind() == Kind.STEP || e.kind() == Kind.STEP_START)
                .map(Event::msg).toList();
    }

    private static boolean isLine(Event event) {
        return event.msg() != null && switch (event.kind()) {
            case STEP_PROGRESS, STEP_DONE, GROUP_END -> false;
            default -> true;
        };
    }
}
