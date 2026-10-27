package dev.incusspawn.command;

import dev.incusspawn.proxy.ProxyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A foreground proxy that exits non-zero is explained in one banner (#1703): its cause, the log's
 * last lines under it, and, unless its configuration was at fault, how to get one that recovers.
 */
class ProxyStartAbnormalExitTest {

    @TempDir
    Path dir;

    @Test
    void theBannerHoldsTheLogsLastLinesAndTheAdvice() throws Exception {
        var log = dir.resolve("proxy.log");
        Files.write(log, IntStream.rangeClosed(1, 12).mapToObj(i -> "line " + i).toList());

        var lines = List.of(ProxyStartCommand.abnormalExitLines(137, log));

        assertEquals("Last lines of the proxy log (" + log + "):", lines.get(1));
        assertEquals("  line 3", lines.get(2), "the last ten lines, under what introduces them");
        assertEquals("  line 12", lines.get(11));
        assertTrue(lines.getLast().contains("isx proxy install"), lines::toString);
    }

    /** The banner prints its lines as given, so a log line cannot drive the terminal. */
    @Test
    void aLogLineIsShownAsText() throws Exception {
        var log = dir.resolve("proxy.log");
        Files.writeString(log, "GET /\u001b]0;title\u0007x\n");

        var shown = ProxyStartCommand.abnormalExitLines(137, log)[2];

        assertTrue(shown.chars().noneMatch(c -> c == 0x1b || c == 0x07), shown);
    }

    @Test
    void aConfigurationErrorGetsNoAdviceToInstallTheService() {
        var lines = List.of(ProxyStartCommand.abnormalExitLines(ProxyService.EXIT_CONFIG, dir.resolve("absent.log")));

        assertEquals(List.of("", "Last lines of the proxy log (" + dir.resolve("absent.log") + "):",
                "  (no log file found)"), lines);
        assertFalse(String.join("\n", lines).contains("isx proxy install"));
    }
}
