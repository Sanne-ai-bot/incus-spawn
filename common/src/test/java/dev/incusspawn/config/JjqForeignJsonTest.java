package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON another program wrote (Jjq.parseForeign): a lone surrogate escape, which JSON.stringify
 * writes for a string cut inside a pair, reads as U+FFFD, and nothing else that strict parsing
 * refuses is let through. Each refusal names the source and says what is wrong and where.
 */
class JjqForeignJsonTest {

    @Test
    void aLoneSurrogateEscapeReadsAsTheReplacementCharacter() {
        var read = Jjq.parseForeign("{\"a\":\"cut \\ud83d\",\"b\":\"\\ude00 x\",\"c\\ud800\":\"\\ud83d\\ud83d\"}", "x.json");
        assertEquals("cut \ufffd", read.path("a").asText());
        assertEquals("\ufffd x", read.path("b").asText());
        assertEquals("\ufffd\ufffd", read.path("c\ufffd").asText(), "a key too, and a high followed by a high");
    }

    @Test
    void aPairAndAnEscapedBackslashAreKept() {
        var read = Jjq.parseForeign("{\"a\":\"\\ud83d\\ude00 \\\\ud800 \\ud800\"}", "x.json");
        assertEquals("\ud83d\ude00 \\ud800 \ufffd", read.path("a").asText());
    }

    @Test
    void bytesAreReadTheSameWayAroundOtherUtf8() {
        var json = "{\"é\":\"ü \\ud83d 😀\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("ü \ufffd 😀", Jjq.parseForeign(json, "x.json").path("é").asText());
    }

    @Test
    void aCleanDocumentIsReadAsIs() {
        assertEquals(JqValues.parseStrict("{\"a\":[1,\"\\u00e9\"]}"), Jjq.parseForeign("{\"a\":[1,\"\\u00e9\"]}", "x.json"));
    }

    /** What parseForeign recognises; if jjq rewords it, the lenient path stops and the tests above fail too. */
    @Test
    void strictParsingStillRefusesALoneSurrogate() {
        var e = assertThrows(IllegalArgumentException.class, () -> JqValues.parseStrict("{\"a\":\"\\ud83d\"}"));
        assertTrue(e.getMessage().startsWith("Unpaired surrogate escape"), e.getMessage());
    }

    @Test
    void everyOtherMalformationIsRefusedSayingWhatAndWhere() {
        assertRefused("{\"a\":\"\\q\"}", "Invalid escape '\\q' at line 1, column 8");
        assertRefused("{\"a\":\"x\"", "Unterminated object at line 1, column 9");
        assertRefused("{\"a\":1} x", "Invalid numeric literal at EOF at line 1, column 9");
        assertRefused("{\"a\":\"x}", "Unterminated string");
        assertRefused("{\"a\":\"\\u\uFF24800\"}", "Invalid \\u escape sequence at line 1, column 8");
        assertRefused("{\"a\":1,\"a\":2}", "Duplicate key \"a\" at line 1, column 8");
        assertRefused("{\n\"a\":\ntru}", "Invalid literal, expected 'true' at line 3, column 1");
    }

    @Test
    void aLoneSurrogateDoesNotExcuseAnotherFault() {
        // The replacement keeps every length, so the position is the one in the document as written
        assertRefused("{\"a\":\"\\ud83d\",\"b\":\"\\q\"}", "Invalid escape '\\q' at line 1, column 21");
        assertRefused("{\"a\":\"\\ud83d\"} x", "Invalid numeric literal at EOF at line 1, column 16");
        assertRefused("{\"a\":\"\\ud83d", "Unterminated string");
    }

    @Test
    void invalidUtf8IsRefusedWithOrWithoutALoneSurrogate() {
        var bad = new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, '"', '}'};
        var e = assertThrows(IllegalArgumentException.class, () -> Jjq.parseForeign(bad, "x.json"));
        assertEquals("x.json is not valid JSON: Invalid UTF-8 at line 1, column 7", e.getMessage());

        var both = "{\"a\":\"\\ud83d\",\"b\":\"_\"}".getBytes(StandardCharsets.ISO_8859_1);
        both[both.length - 3] = (byte) 0xC3;
        var again = assertThrows(IllegalArgumentException.class, () -> Jjq.parseForeign(both, "x.json"));
        assertTrue(again.getMessage().startsWith("x.json is not valid JSON: Invalid UTF-8 at line 1"), again.getMessage());
    }

    @Test
    void theMessageNeverQuotesTheDocument() {
        var e = assertThrows(IllegalArgumentException.class, () -> Jjq.parseForeign("{\"secret\":\"s3cr3t\"} x", "x.json"));
        assertFalse(e.getMessage().contains("s3cr3t"), e.getMessage());
        assertNull(e.getCause(), "jjq's own message quotes the document, so a stack trace would too");
        assertTrue(e.getStackTrace()[0].getClassName().startsWith("io.hyperfoil.tools.jjq"), "where jjq refused");
    }

    private static void assertRefused(String json, String why) {
        var e = assertThrows(IllegalArgumentException.class, () -> Jjq.parseForeign(json, "x.json"), json);
        assertEquals("x.json is not valid JSON: " + why, e.getMessage(), json);
        var bytes = assertThrows(IllegalArgumentException.class,
                () -> Jjq.parseForeign(json.getBytes(StandardCharsets.UTF_8), "x.json"), json);
        assertEquals("x.json is not valid JSON: " + why, bytes.getMessage(), json);
    }
}
