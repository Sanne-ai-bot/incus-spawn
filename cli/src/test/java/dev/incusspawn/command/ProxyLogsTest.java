package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How following the proxy's log ends decides {@code isx proxy logs}' exit code. */
class ProxyLogsTest {

    @Test
    void aFollowerStoppedByASignalIsASuccess() {
        assertEquals(0, ProxyCommand.Logs.followed(0));
        // `isx proxy logs | head`: tail gets SIGPIPE
        assertEquals(0, ProxyCommand.Logs.followed(128 + 13));
        assertEquals(0, ProxyCommand.Logs.followed(128 + 15));
    }

    @Test
    void aFollowerThatCouldNotReadTheLogIsAFailure() {
        assertEquals(1, ProxyCommand.Logs.followed(1));
        assertEquals(1, ProxyCommand.Logs.followed(2));
    }
}
