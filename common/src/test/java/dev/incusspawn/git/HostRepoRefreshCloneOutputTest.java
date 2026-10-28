package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a host clone that fails reports (#1703). */
class HostRepoRefreshCloneOutputTest {

    @TempDir Path tmp;

    /** One warning naming the repository, Git's reasons under it, which the TUI's log and a recorder keep. */
    @Test
    void aFailedCloneIsOneWarningWithItsReasons() {
        var config = new SpawnConfig();
        config.setHostPaths(List.of(tmp.toString()));
        config.setAutoCloneRepos("always");
        var repo = new ImageDef.RepoEntry();
        // A reserved name that never resolves, so no server or credential is asked. The clone is the
        // host's own Git, which reads the host's Git config (a configured http.proxy would be asked),
        // and HostRepoRefresh has no seam to isolate it as HermeticGit does a test's own process.
        repo.setUrl("https://isx-hermetic.invalid/none.git");

        var recorded = RecordingOutput.during(() ->
                HostRepoRefresh.refresh(List.of(repo), Map.of(), config, true, line -> {}));

        var warnings = recorded.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warnings.size(), recorded.events().toString());
        assertTrue(warnings.getFirst().msg().startsWith("Clone failed for https://isx-hermetic.invalid/none.git"), warnings.getFirst().msg());
        assertFalse(warnings.getFirst().details().isEmpty(), "Git's reasons are its details");
    }
}
