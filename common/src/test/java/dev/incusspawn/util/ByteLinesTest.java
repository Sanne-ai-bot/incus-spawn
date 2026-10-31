package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteLinesTest {

    /** Split as BufferedReader.readLine splits: on LF, CR and CRLF, a final line unterminated. */
    @Test
    void splitsOnEveryLineEndReadLineAccepts() throws IOException {
        assertEquals(List.of("a", "b", "c", "", "d"), lines("a\nb\r\nc\r\rd"));
        assertEquals(List.of("a", ""), lines("a\r\n\n"));
        assertEquals(List.of(), lines(""));
    }

    private static List<String> lines(String text) throws IOException {
        var out = new ArrayList<String>();
        try (var lines = new ByteLines(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)))) {
            byte[] line;
            while ((line = lines.next()) != null) out.add(new String(line, StandardCharsets.US_ASCII));
        }
        return out;
    }
}
