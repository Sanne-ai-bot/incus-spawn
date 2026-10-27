package dev.incusspawn.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.ForcedAnsi;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AskCommandTest {

    // Clear the screen, retitle the window, an 8-bit CSI, split across chunks as a stream may be.
    private static final String[] CHUNKS = {"one\033[2J", "\033]0;title\007 two\n", "three\u009b31m\r\n", "\tfour\033"};

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void answerCannotDriveTheTerminal(boolean ansi) {
        assertEquals("one [2J ]0;title  two\nthree 31m \n\tfour ", written(ansi));
    }

    private static String written(boolean ansi) {
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var writer = new AskCommand.AnswerWriter(out, ansi);
        for (var chunk : CHUNKS) writer.accept(chunk);
        writer.finish();
        return bytes.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    /**
     * The question is a live step that ends with the first chunk, and the answer is the
     * command's result, so the TUI's log and a recorder see both (#1703).
     */
    @Test
    void theQuestionIsAStepAndTheAnswerItsResult() {
        var recorded = RecordingOutput.during(() -> AskCommand.answer("Asking Claude...", chunks -> {
            chunks.accept("Use isx ");
            chunks.accept("branch.\nDone.");
        }));
        assertEquals(List.of(RecordingOutput.Kind.STEP_START, RecordingOutput.Kind.STEP_DONE, RecordingOutput.Kind.FRAME),
                recorded.events().stream().map(RecordingOutput.Event::kind)
                        .filter(k -> k != RecordingOutput.Kind.RESULT).toList());
        assertEquals("Asking Claude...", recorded.events().getFirst().msg());
        assertEquals(List.of("Use isx branch.", "Done."), recorded.resultLines());
    }

    /** A stream that fails before anything came breaks the step, so the error follows a closed line. */
    @Test
    void aStreamThatFailsAtOnceBreaksTheStep() {
        var recorded = new RecordingOutput();
        try (var bound = BuildOutput.bind(recorded)) {
            assertThrows(java.io.IOException.class, () -> AskCommand.answer("Asking Claude...", chunks -> {
                throw new java.io.IOException("HTTP 529");
            }));
        }
        assertEquals(List.of(RecordingOutput.Kind.STEP_START, RecordingOutput.Kind.STEP_BREAK),
                recorded.events().stream().map(RecordingOutput.Event::kind).toList());
    }

    /** A stream that fails part way keeps what came, ended on a line of its own before the error. */
    @Test
    void aStreamThatFailsPartWayEndsWhatCameOnItsOwnLine() {
        var recorded = new RecordingOutput();
        try (var bound = BuildOutput.bind(recorded)) {
            assertThrows(java.io.IOException.class, () -> AskCommand.answer("Asking Claude...", chunks -> {
                chunks.accept("");
                chunks.accept("Use isx bra");
                throw new java.io.IOException("timed out");
            }));
        }
        assertEquals(List.of("Use isx bra"), recorded.resultLines());
        assertEquals(List.of(RecordingOutput.Kind.STEP_START, RecordingOutput.Kind.STEP_DONE, RecordingOutput.Kind.FRAME),
                recorded.events().stream().map(RecordingOutput.Event::kind)
                        .filter(k -> k != RecordingOutput.Kind.RESULT).toList());
    }

    /**
     * On an ANSI terminal the answer is written a whole line at a time: a blank line sets it apart
     * from the step, and a stream that fails part way still shows the line it was on.
     */
    @Test
    void onAnAnsiTerminalAPartialLineIsKeptAfterAFailure() {
        var recorded = new RecordingOutput();
        try (var ansi = ForcedAnsi.on(); var bound = BuildOutput.bind(recorded)) {
            assertThrows(java.io.IOException.class, () -> AskCommand.answer("Asking Claude...", chunks -> {
                chunks.accept("First line.\nSecond li");
                throw new java.io.IOException("timed out");
            }));
        }
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.FRAME, "", "")),
                recorded.events(RecordingOutput.Kind.FRAME));
        assertEquals(List.of("First line.", "Second li"), recorded.resultLines());
    }
}
