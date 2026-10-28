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
 * Refreshes of host checkouts of a repository whose commits each replace a binary file, with the
 * real git against a local fixture (#1344). A shallow checkout whose depth isx manages is one an
 * earlier isx cloned, marked with {@link HostRepoRefresh#DEPTH_KEY}; isx no longer clones one (#1789).
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
    }

    @Test
    void aManagedShallowCheckoutStaysShallowOnRefresh() throws Exception {
        managedClone(2);
        commitBinary(upstream, "release 6");
        commitBinary(upstream, "release 7");
        commitBinary(upstream, "release 8");
        refresh(repo(2, null));
        assertEquals("release 8", git(host, "log", "-1", "--format=%s", "origin/main"));
        assertEquals("2", git(host, "rev-list", "--count", "origin/main"));
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
    void anotherTemplateWantingFullHistoryDeepensAManagedShallowCheckout() throws Exception {
        managedClone(1);
        refresh(repo(1, null), repo(null, null));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(host, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aTemplateOutsideTheBuildWantingFullHistoryDeepensAManagedShallowCheckout() throws Exception {
        managedClone(1);
        // Only the shallow template is built, but another one names the URL without a depth.
        refresh(List.of(repo(1, null)), repo(1, null), repo(null, null));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(host, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowHostCloneIsDeepenedOnceATemplateWantsTheFullHistory() throws Exception {
        managedClone(1);
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

    /** A build of templates naming {@code repos}, and nothing else. */
    private void refresh(ImageDef.RepoEntry... repos) {
        refresh(List.of(repos), repos);
    }

    /** A build of templates naming {@code built}, with {@code everyTemplate} all the repos defined. */
    private void refresh(List<ImageDef.RepoEntry> built, ImageDef.RepoEntry... everyTemplate) {
        HostRepoRefresh.refresh(built, HostRepoRefresh.hostDepths(List.of(everyTemplate)), config, s -> {});
    }

    /** The host checkout an earlier isx cloned for a template asking for {@code depth}. */
    private void managedClone(int depth) throws Exception {
        git(tmp, "clone", "-q", "--depth", Integer.toString(depth), "-c", HostRepoRefresh.DEPTH_KEY + "=" + depth,
                "--", upstream.toUri().toString(), host.toString());
        assertEquals("true", git(host, "rev-parse", "--is-shallow-repository"));
    }

    private ImageDef.RepoEntry repo(Integer depth, String branch) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(upstream.toUri().toString());
        repo.setBranch(branch);
        repo.setDepth(depth);
        return repo;
    }

}
