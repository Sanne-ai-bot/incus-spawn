package dev.incusspawn.git;

import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.config.GitTransferConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** A build's clean-snapshot preparation against a remote that accepts and never answers (#1343). */
class GitPreparationStallTest {
    @TempDir Path tmp;

    @Test void aStalledRemoteFailsPreparationPromptlySoTheBuildCanCloneInTheGuest() throws Exception {
        try (var silent = new SilentRemote()) {
            var preparation = new GitPreparation(tmp.resolve("cache"), GitCacheConfig.DEFAULT,
                    new GitTransferConfig(Duration.ofSeconds(1), Duration.ofMinutes(1)));
            var request = new GitPreparation.Request(silent.url("git", "repo.git"), null, null);

            var stopped = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> assertThrows(PreparationGit.TransferStopped.class, () -> preparation.prepare(request, phase -> {})));
            assertEquals("Git preparation ls-remote stalled: no output, I/O or CPU work for 1s", stopped.getMessage());
        }
    }

    @Test void theReportedCommandSkipsConfigOptions() {
        assertEquals("clone", PreparationGit.verb(java.util.List.of("-c", "core.x=y", "clone", "--bare")));
        assertEquals("git", PreparationGit.verb(java.util.List.of("-c", "a=b")));
    }
}
