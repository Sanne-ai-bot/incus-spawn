package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.command.BuildProgress.StepProgress;
import dev.incusspawn.command.BuildProgress.StepState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static dev.incusspawn.git.GitTestUtils.commitBinary;
import static dev.incusspawn.git.GitTestUtils.git;
import static dev.incusspawn.git.GitTestUtils.releasesFixture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Runs the guest's clone commands with the real git against a local fixture whose commits each
 * replace a binary file, as a release repository does (#1344).
 */
class RepoClonerShallowTest {

    @TempDir Path tmp;
    private Path upstream;
    private IncusClient incus;
    private Container container;

    @BeforeEach
    void setUp() throws Exception {
        upstream = releasesFixture(tmp);

        incus = mock(IncusClient.class);
        container = new Container(incus, "test");
        // The guest is this machine; a reference is a plain directory here, so it needs no mount wait.
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenAnswer(inv -> {
            String cmd = inv.getArgument(2);
            return sh(cmd.replaceAll("\\{ ! command -v mountpoint .*?; \\} && ", ""));
        });
    }

    @Test
    void aShallowRepoClonesFromTheNetworkWithItsDepthAndStaysOnItsBranch() throws Exception {
        var guest = prepare(repo(2, null), null);

        assertEquals("true", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("2", git(guest, "rev-list", "--count", "HEAD"));
        assertEquals("+refs/heads/main:refs/remotes/origin/main", git(guest, "config", "--get-all", "remote.origin.fetch"),
                "a later fetch must not pull every other branch's history");

        commitBinary(upstream, "release 6");
        git(guest, "pull", "-q", "--ff-only");
        assertEquals("true", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("release 6", git(guest, "log", "-1", "--format=%s"));
    }

    @Test
    void aShallowRepoChecksOutItsConfiguredBranch() throws Exception {
        var guest = prepare(repo(1, "stable"), null);

        assertEquals("stable", git(guest, "branch", "--show-current"));
        assertEquals("1", git(guest, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowRepoClonedFromAFullHostCheckoutIsShallowAndLeavesTheHostAlone() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        commitBinary(upstream, "release 6"); // the host refresh has not seen it yet

        var guest = prepare(repo(2, null), host);

        assertEquals("true", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("2", git(guest, "rev-list", "--count", "HEAD"));
        assertEquals("release 6", git(guest, "log", "-1", "--format=%s"));
        assertEquals(upstream.toUri().toString(), git(guest, "remote", "get-url", "origin"));
        assertEquals("false", git(host, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(host, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowRepoClonedFromAShallowHostCheckoutGetsItsConfiguredBranch() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--depth", "1", "--", upstream.toUri().toString(), host.toString());

        var guest = prepare(repo(1, "stable"), host);

        assertEquals("stable", git(guest, "branch", "--show-current"));
        assertEquals("stable release", git(guest, "log", "-1", "--format=%s"));
        assertEquals("1", git(guest, "rev-list", "--count", "HEAD"));
    }

    @Test
    void withoutADepthEveryCloneKeepsTheFullHistory() throws Exception {
        var fromNetwork = prepare(repo(null, null), null);
        assertEquals("false", git(fromNetwork, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(fromNetwork, "rev-list", "--count", "HEAD"));

        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        var fromHost = prepare(repo(null, null), host);
        assertEquals("false", git(fromHost, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(fromHost, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aShallowRepoLeavesNothingOfTheHostCheckoutsOwnBranch() throws Exception {
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--", upstream.toUri().toString(), host.toString());
        git(host, "checkout", "-q", "-b", "wip");
        commitBinary(host, "unpushed work");
        var unpushed = git(host, "rev-parse", "HEAD");
        git(host, "tag", "private-wip");
        git(host, "tag", "it's-wip"); // a quote, which git allows in a tag name

        var guest = prepare(repo(2, null), host);

        var p = new ProcessBuilder("git", "-C", guest.toString(), "cat-file", "-e", unpushed).start();
        assertNotEquals(0, p.waitFor(), "the host's unpushed commit is still in the guest's objects");
        assertEquals("", git(guest, "tag", "-l"));
        assertFalse(git(guest, "reflog", "--all").contains(unpushed.substring(0, 7)));

        assertEquals("refs/heads/main\nrefs/remotes/origin/HEAD refs/remotes/origin/main\nrefs/remotes/origin/main",
                git(guest, "for-each-ref", "--format=%(refname) %(symref)", "refs/heads", "refs/remotes")
                        .replaceAll(" +\n", "\n"));
        assertEquals("release 5", git(guest, "log", "-1", "--format=%s"));
    }

    @Test
    void aRepoWithoutADepthDoesNotInheritAShallowHostCheckout() throws Exception {
        // Shallow because another template gave this URL a depth when the host cloned it.
        var host = tmp.resolve("host-releases");
        git(tmp, "clone", "-q", "--depth", "1", "--", upstream.toUri().toString(), host.toString());

        var repo = repo(null, null);
        var states = new AtomicReferenceArray<StepProgress>(1);
        new RepoCloner(incus).prepareOne(container, repo,
                new RepoCloner.RepoReference("ref", host.toString(), host.toString(), null),
                states, 0, new AtomicBoolean());

        var guest = Path.of(repo.getPath());
        assertEquals(StepState.DONE, states.get(0).state(), states.get(0).detail());
        assertEquals("false", git(guest, "rev-parse", "--is-shallow-repository"));
        assertEquals("5", git(guest, "rev-list", "--count", "HEAD"));
        assertTrue(states.get(0).note().contains("host checkout is shallow"), states.get(0).note());
    }

    private int guests;

    private ImageDef.RepoEntry repo(Integer depth, String branch) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(upstream.toUri().toString());
        repo.setPath(tmp.resolve("guest-" + ++guests).toString());
        repo.setBranch(branch);
        repo.setDepth(depth);
        return repo;
    }

    private Path prepare(ImageDef.RepoEntry repo, Path reference) {
        var ref = reference == null ? null
                : new RepoCloner.RepoReference("ref", reference.toString(), reference.toString(), null);
        var states = new AtomicReferenceArray<StepProgress>(1);
        new RepoCloner(incus).prepareOne(container, repo, ref, states, 0, new AtomicBoolean());
        var state = states.get(0);
        assertEquals(StepState.DONE, state.state(), state.detail() + "\n" + state.log());
        if (reference != null) assertEquals("via host reference", state.note());
        return Path.of(repo.getPath());
    }


    private static IncusClient.ExecResult sh(String command) throws Exception {
        var p = new ProcessBuilder("sh", "-c", command).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new IncusClient.ExecResult(p.waitFor(), out, err);
    }
}
