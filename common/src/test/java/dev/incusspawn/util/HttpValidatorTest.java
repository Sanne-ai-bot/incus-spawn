package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpValidatorTest {

    /** The rest of a 100-byte file from byte 40, as a 206 that continues it answers. */
    private static Map<String, String> rest() {
        var headers = new HashMap<String, String>();
        headers.put("ETag", "\"v1\"");
        headers.put("Content-Range", "bytes 40-99/100");
        headers.put("Content-Length", "60");
        return headers;
    }

    private static boolean continues(String validator, Map<String, String> headers) {
        return HttpValidator.continues(validator, 100, 40, headers::get);
    }

    @Test
    void theRestOfTheSameFileContinuesIt() {
        assertTrue(continues("\"v1\"", rest()));
    }

    @Test
    void anotherValidatorDoesNotContinueIt() {
        assertFalse(continues("\"v0\"", rest()));
        var noValidator = rest();
        noValidator.remove("ETag");
        assertFalse(continues("\"v1\"", noValidator));
    }

    @Test
    void anEncodedRestDoesNotContinueIt() {
        var encoded = rest();
        encoded.put("Content-Encoding", "br");
        assertFalse(continues("\"v1\"", encoded));
    }

    @Test
    void aRestOfAnotherLengthDoesNotContinueIt() {
        for (var length : new String[] {"76", "30", "sixty", null}) {
            var headers = rest();
            headers.put("Content-Length", length);
            assertFalse(continues("\"v1\"", headers), "Content-Length " + length);
        }
    }

    @Test
    void anotherRangeDoesNotContinueIt() {
        var headers = rest();
        headers.put("Content-Range", "bytes 40-99/120");
        assertFalse(continues("\"v1\"", headers));
    }

    @Test
    void withoutAValidatorNothingContinues() {
        assertFalse(continues(null, rest()));
    }

    @Test
    void lastModifiedStandsInOnlyWithoutAnEtagAndASecondBeforeDate() {
        var headers = new HashMap<String, String>();
        headers.put("Last-Modified", "Tue, 29 Sep 2026 08:10:59 GMT");
        headers.put("Date", "Wed, 30 Sep 2026 09:00:00 GMT");
        assertEquals("Tue, 29 Sep 2026 08:10:59 GMT", HttpValidator.strong(headers::get));
        headers.put("Date", "Tue, 29 Sep 2026 08:10:59 GMT");
        assertNull(HttpValidator.strong(headers::get), "modified in the second it was sent");
        headers.put("ETag", "W/\"v1\"");
        assertNull(HttpValidator.strong(headers::get), "a weak ETag leaves none");
    }

    @Test
    void contentLengthIsMinusOneWhenAbsentOrNotANumber() {
        assertEquals(60, HttpValidator.contentLength(Map.of("Content-Length", " 60 ")::get));
        assertEquals(-1, HttpValidator.contentLength(Map.of("Content-Length", "sixty")::get));
        assertEquals(-1, HttpValidator.contentLength(Map.<String, String>of()::get));
    }
}
