package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the API traffic log shows of a body: all of one handed over whole, as {@code isx proxy dump}
 * does, and a marked prefix of one the proxy captured as it passed (#1315).
 */
class ApiTrafficLogTest {

    @TempDir
    Path dir;

    static String json(int size) {
        return "{\"messages\":[\"" + "x".repeat(size) + "\"],\"last\":true}";
    }

    String onlyLog() throws Exception {
        try (var files = Files.list(dir)) {
            return Files.readString(files.findFirst().orElseThrow());
        }
    }

    /** {@code isx proxy dump} holds the body whole: its log shows all of it, pretty-printed, however long. */
    @Test
    void aBodyHandedOverWholeIsLoggedWhole() throws Exception {
        var body = json(300 * 1024).getBytes(StandardCharsets.UTF_8);
        new ApiTrafficLog(dir).logExchange("POST /v1/messages HTTP/1.1\nHost: api.anthropic.com\n", body,
                null, null, "HTTP/1.1 200 OK\n", null);
        var log = onlyLog();
        assertFalse(log.contains("[captured"), "cut at the capture");
        assertTrue(log.contains("\"last\": true"), "the end of the body, pretty-printed");
    }

    /** A body captured as it passed is cut at 64 KiB, says so, and shows what an uncut raw one would. */
    @Test
    void aCapturedBodyIsMarkedWhereItWasCut() throws Exception {
        var capture = new ApiTrafficLog.Capture();
        var body = "y".repeat(100 * 1024).getBytes(StandardCharsets.UTF_8);
        capture.accept(body.length, (start, end, dst, dstIndex) -> System.arraycopy(body, start, dst, dstIndex, end - start));
        new ApiTrafficLog(dir).logExchange("POST /upload HTTP/1.1\n", capture, null, null, "HTTP/1.1 200 OK\n", null);
        var log = onlyLog();
        assertTrue(log.contains("[captured " + ApiTrafficLog.MAX_BODY_CAPTURE + " of " + body.length + " bytes]"), log.substring(0, 200));
        var shown = log.substring(log.indexOf("bytes]\n") + "bytes]\n".length());
        assertEquals(4096, shown.chars().filter(c -> c == 'y').count(), "as much as an uncut raw body shows");
    }
}
