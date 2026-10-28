package dev.incusspawn.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The host commands a host integration runs are bounded, a daemon left holding the output included. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class HostCommandsTest {

    @Test
    void outputAndExitStatusAreReturned() {
        var result = HostCommands.SYSTEM.run(Duration.ofSeconds(10), List.of("sh", "-c", "echo out; echo err >&2; exit 3"));
        assertEquals(3, result.exit());
        assertTrue(result.output().contains("out") && result.output().contains("err"), result.output());
    }

    @Test
    void aCommandPastItsTimeIsKilled() {
        var start = System.nanoTime();
        var result = HostCommands.SYSTEM.run(Duration.ofSeconds(1), List.of("sleep", "30"));
        assertEquals(-1, result.exit());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }

    /** It exits, but a child it started in the background still holds the output pipe open. */
    @Test
    void aDaemonLeftHoldingTheOutputDoesNotHoldTheCaller() {
        var start = System.nanoTime();
        var result = HostCommands.SYSTEM.run(Duration.ofSeconds(2), List.of("sh", "-c", "sleep 30 & echo started"));
        assertEquals(0, result.exit());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10, "bounded by its timeout");
    }

    @Test
    void aCommandIsLookedUpOnThePath() {
        assertTrue(HostCommands.SYSTEM.onPath("sh"));
        assertFalse(HostCommands.SYSTEM.onPath("no-such-command-isx-1720"));
    }
}
