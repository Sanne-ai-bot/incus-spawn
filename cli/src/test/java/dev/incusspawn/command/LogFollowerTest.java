package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How following a log ends decides the exit code of {@code isx proxy logs} and {@code isx vm console}. */
class LogFollowerTest {

    @Test
    void aFollowerStoppedByASignalIsASuccess() {
        assertEquals(0, LogFollower.followed(0));
        // `isx proxy logs | head`: tail gets SIGPIPE
        assertEquals(0, LogFollower.followed(128 + 13));
        assertEquals(0, LogFollower.followed(128 + 15));
    }

    @Test
    void aFollowerThatCouldNotReadTheLogIsAFailure() {
        assertEquals(1, LogFollower.followed(1));
        assertEquals(1, LogFollower.followed(2));
    }
}
