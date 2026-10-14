package dev.incusspawn.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
}
