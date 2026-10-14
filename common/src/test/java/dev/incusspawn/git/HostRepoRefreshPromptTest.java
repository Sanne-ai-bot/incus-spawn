package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.git.GitTestUtils.git;
import static dev.incusspawn.git.GitTestUtils.releasesFixture;
import static org.junit.jupiter.api.Assertions.*;

/** A host refresh whose SSH would need a prompt: it fails at once and quotes what ssh said (#1343). */
class HostRepoRefreshPromptTest {

    @TempDir Path tmp;

    @Test
    void anSshAuthFailureFailsFastAndKeepsGitsWordsApartFromIsxs() throws Exception {
        var host = tmp.resolve("host").resolve("releases");
        git(tmp, "clone", "-q", releasesFixture(tmp).toString(), host.toString());
        var url = "git@example.invalid:org/releases.git";
        git(host, "remote", "set-url", "origin", url);
        // The user's own SSH command, which update-all keeps: here, one whose key the server refuses.
        var ssh = Files.writeString(tmp.resolve("refused-ssh"),
                "#!/bin/sh\necho 'git@example.invalid: Permission denied (publickey).' >&2\nexit 255\n");
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
        git(host, "config", "core.sshCommand", ssh.toString());

        var config = new SpawnConfig();
        config.setRepoPaths(Map.of("releases", host.toString()));
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url);
        var output = new ArrayList<String>();
        long start = System.nanoTime();
        HostRepoRefresh.refresh(List.of(repo), Map.of(), config, false, output::add);
        assertTrue(System.nanoTime() - start < 30_000_000_000L, "fails fast");

        var lines = String.join("\n", output).lines().toList();
        var warning = lines.stream().filter(l -> l.contains("Warning: fetch failed for releases")).findFirst().orElseThrow();
        assertFalse(warning.contains("Permission denied"), "isx's warning holds none of Git's words: " + warning);
        int quoted = lines.indexOf(lines.stream().filter(l -> l.strip().equals("Git's output:")).findFirst().orElseThrow());
        // update-all ran the user's own SSH command as it is: isx claims no part in the refusal.
        assertEquals(warning, lines.get(quoted - 1), "no isx note between the warning and Git's words: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.contains("BatchMode")), lines.toString());
        assertTrue(lines.subList(quoted + 1, lines.size()).stream()
                        .anyMatch(l -> l.strip().equals("| git@example.invalid: Permission denied (publickey).")),
                "ssh's words are quoted: " + lines);
    }
}
