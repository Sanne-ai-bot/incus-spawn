package dev.incusspawn.proxy;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy daemon's one gate for what it says (#1703): a diagnostic with advice keeps every
 * line in the lifecycle log, each at its level, and a request's trace never reaches that log.
 */
class ProxyLogTest {

    @TempDir
    Path home;
    private String savedHome;
    private boolean savedSuppress;

    @BeforeEach
    void isolate() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        // The lifecycle log is what is asserted; stderr stays quiet
        savedSuppress = ProxyLog.setSuppressStderr(true);
    }

    @AfterEach
    void restore() {
        ProxyLog.setSuppressStderr(savedSuppress);
        System.setProperty("user.home", savedHome);
    }

    @Test
    void anErrorWithAdviceKeepsEveryLineInTheLifecycleLog() throws Exception {
        ProxyLog.error("Failed to start: port in use", "Is another proxy already running?", "Re-run 'isx init'.");
        ProxyLog.warn("Could not read the Incus bridge address", "Is Incus running?");

        var lines = Files.readAllLines(Environment.proxyLifecycleLogFile());
        assertEquals(5, lines.size(), lines::toString);
        assertTrue(lines.get(0).endsWith("[ERROR] Failed to start: port in use"), lines.get(0));
        assertTrue(lines.get(1).endsWith("[ERROR] Is another proxy already running?"), lines.get(1));
        assertTrue(lines.get(4).endsWith("[WARN] Is Incus running?"), lines.get(4));
    }

    @Test
    void aRequestsTraceStaysOutOfTheLifecycleLog() {
        // Printed, so the trace runs all the way: only the lifecycle log is asserted
        ProxyLog.setSuppressStderr(false);
        ProxyLog.trace("Upstream connect error (example.com): refused", new IllegalStateException("refused"));
        ProxyLog.trace("Cached: org/example/1.0/x.jar (sha1 verified)");

        assertTrue(Files.notExists(Environment.proxyLifecycleLogFile()), "a trace line reached the lifecycle log");
    }
}
