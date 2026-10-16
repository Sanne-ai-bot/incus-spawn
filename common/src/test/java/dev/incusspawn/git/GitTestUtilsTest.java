package dev.incusspawn.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.incusspawn.git.GitTestUtils.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GitTestUtilsTest {
    @TempDir Path tmp;

    /**
     * A fixture's commits start no auto maintenance: Git detaches it, so it can still write into the
     * repository after the test, racing {@code @TempDir} cleanup (#1473). Here maintenance would run in
     * the foreground and pack the loose objects, so a run of it shows as a pack.
     */
    @Test void fixtureCommitsRunNoAutoMaintenance() throws Exception {
        var repo = tmp.resolve("repo");
        runGit(tmp, "init", "-q", repo.toString());
        runGit(repo, "config", "maintenance.autoDetach", "false");
        runGit(repo, "config", "maintenance.loose-objects.enabled", "true");
        runGit(repo, "config", "maintenance.loose-objects.auto", "1");
        commitBinary(repo, "release");
        try (var packs = Files.list(repo.resolve(".git/objects/pack"))) {
            assertFalse(packs.findAny().isPresent(), "auto maintenance packed the fixture's objects");
        }
    }
}
