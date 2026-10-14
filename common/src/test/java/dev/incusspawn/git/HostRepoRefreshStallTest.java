package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.git.GitTestUtils.git;
import static dev.incusspawn.git.GitTestUtils.releasesFixture;
import static org.junit.jupiter.api.Assertions.*;

/** A host refresh whose remote accepts the connection and then never answers (#1343). */
class HostRepoRefreshStallTest {

    @TempDir Path tmp;

    @Test
    void aStalledFetchIsKilledAndReportedAsANonFatalWarning() throws Exception {
        var host = tmp.resolve("host").resolve("releases");
        git(tmp, "clone", "-q", releasesFixture(tmp).toString(), host.toString());
        var head = git(host, "rev-parse", "origin/main");

        try (var silent = new SilentRemote()) {
            var url = silent.url("http", "releases.git");
            git(host, "remote", "set-url", "origin", url);

            var config = new SpawnConfig();
            config.setRepoPaths(Map.of("releases", host.toString()));
            config.setConfigByPath("git-transfer.stall", "2s");
            var repo = new ImageDef.RepoEntry();
            repo.setUrl(url);
            var output = new ArrayList<String>();

            assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                    HostRepoRefresh.refresh(List.of(repo), Map.of(), config, false, output::add));

            assertTrue(output.stream().anyMatch(l -> l.contains("fetch failed for releases") && l.contains("stalled")),
                    "warning names the stall: " + output);
            assertEquals(head, git(host, "rev-parse", "origin/main"), "the existing checkout is untouched");
            assertEquals(List.of(), gitDescendants(), "no Git process survives the stall");
        }
    }

    /** Git processes still running under this JVM, after giving killed ones a moment to be reaped. */
    private static List<Long> gitDescendants() throws InterruptedException {
        List<Long> live = List.of();
        for (int i = 0; i < 50; i++) {
            live = ProcessHandle.current().descendants()
                    .filter(p -> p.info().command().map(c -> c.contains("git")).orElse(false))
                    .map(ProcessHandle::pid).toList();
            if (live.isEmpty()) break;
            Thread.sleep(100);
        }
        return live;
    }
}
