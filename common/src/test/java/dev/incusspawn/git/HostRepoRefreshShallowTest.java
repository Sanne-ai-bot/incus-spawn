package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.git.GitTestUtils.commitBinary;
import static dev.incusspawn.git.GitTestUtils.git;
import static dev.incusspawn.git.GitTestUtils.releasesFixture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Host clones and refreshes of a repository whose commits each replace a binary file, with the
 * real git against a local fixture (#1344).
 */
class HostRepoRefreshShallowTest {

    @TempDir Path tmp;
    private Path upstream;
    private Path host;
    private SpawnConfig config;

    @BeforeEach
    void setUp() throws Exception {
        upstream = releasesFixture(tmp);

        host = tmp.resolve("host").resolve("releases");
        Files.createDirectories(host.getParent());
        config = new SpawnConfig();
        config.setRepoPaths(Map.of("releases", host.toString()));
        config.setAutoCloneRepos("always");
    }

    @Test
    void aShallowRepoIsClonedShallowOnTheHostAndStaysShallowOnRefresh() throws Exception {
        refresh(repo(2, null));
        assertEquals("true", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("2", git(host, "rev-list", "--count", "HEAD"));

        commitBinary(upstream, "release 6");
        commitBinary(upstream, "release 7");
        commitBinary(upstream, "release 8");
        refresh(repo(2, null));
        assertEquals("release 8", git(host, "log", "-1", "--format=%s", "origin/main"));
        assertEquals("2", git(host, "rev-list", "--count", "origin/main"));
    }

    @Test
    void aShallowRepoIsClonedOnTheHostAtItsConfiguredBranch() throws Exception {
        refresh(repo(1, "stable"));
        assertEquals("stable", git(host, "branch", "--show-current"));
        assertEquals("1", git(host, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aFullHostCheckoutIsNeverTruncated() throws Exception {
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        commitBinary(upstream, "release 6");

        refresh(repo(1, null));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("6", git(host, "rev-list", "--count", "origin/main"));
    }

    @Test
    void anotherTemplateWantingFullHistoryKeepsTheHostCloneFull() throws Exception {
        refresh(repo(1, null), repo(null, null));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(host, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aTemplateOutsideTheBuildWantingFullHistoryKeepsTheHostCloneFull() throws Exception {
        // Only the shallow template is built, but another one names the URL without a depth.
        refresh(List.of(repo(1, null)), repo(1, null), repo(null, null));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(host, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowHostCloneIsDeepenedOnceATemplateWantsTheFullHistory() throws Exception {
        refresh(repo(1, null));
        assertEquals("true", git(host, "rev-parse", "--is-shallow-repository"));

        refresh(List.of(repo(null, null)), repo(1, null), repo(null, null));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(host, "rev-list", "--count", "origin/main"));
        assertEquals("stable release", git(host, "log", "-1", "--format=%s", "origin/stable"));
    }

    @Test
    void aUsersDeeperShallowCheckoutIsNotCutDown() throws Exception {
        git(tmp, "clone", "-q", "--depth", "4", "--", upstream.toUri().toString(), host.toString());
        commitBinary(upstream, "release 6");

        refresh(repo(1, null));
        assertEquals("release 6", git(host, "log", "-1", "--format=%s", "origin/main"));
        assertEquals("5", git(host, "rev-list", "--count", "origin/main"));
    }

    @Test
    void aUsersShallowCheckoutIsNotDeepened() throws Exception {
        git(tmp, "clone", "-q", "--depth", "2", "--", upstream.toUri().toString(), host.toString());

        refresh(repo(null, null));
        assertEquals("true", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("2", git(host, "rev-list", "--count", "origin/main"));
    }

    @Test
    void withoutADepthTheHostCloneHasTheFullHistory() throws Exception {
        refresh(repo(null, null));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(host, "rev-list", "--count", "HEAD"));
    }

    /** A build of templates naming {@code repos}, and nothing else. */
    private void refresh(ImageDef.RepoEntry... repos) {
        refresh(List.of(repos), repos);
    }

    /** A build of templates naming {@code built}, with {@code everyTemplate} all the repos defined. */
    private void refresh(List<ImageDef.RepoEntry> built, ImageDef.RepoEntry... everyTemplate) {
        HostRepoRefresh.refresh(built, HostRepoRefresh.hostDepths(List.of(everyTemplate)), config, true, s -> {});
    }

    private ImageDef.RepoEntry repo(Integer depth, String branch) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(upstream.toUri().toString());
        repo.setBranch(branch);
        repo.setDepth(depth);
        return repo;
    }

}
