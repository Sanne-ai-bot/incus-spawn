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

/** A host refresh whose SSH would need a prompt: it fails at once, says what isx changed and quotes ssh (#1343, #1410). */
class HostRepoRefreshPromptTest {

    @TempDir Path tmp;

    @Test
    void theUsersOwnSshCommandCannotPromptAndTheFailureSaysWhatIsxAdded() throws Exception {
        var host = tmp.resolve("host").resolve("releases");
        git(tmp, "clone", "-q", releasesFixture(tmp).toString(), host.toString());
        var url = "git@example.invalid:org/releases.git";
        git(host, "remote", "set-url", "origin", url);
        // The repository's own SSH command (#1410): here, one whose key the server refuses. It says how it ran.
        Files.createDirectories(tmp.resolve("bin"));
        var ssh = Files.writeString(tmp.resolve("bin").resolve("ssh"), "#!/bin/sh\n"
                + "echo \"ran: $1 $2 $3 $4 askpass=$SSH_ASKPASS_REQUIRE\" >&2\n"
                + "echo 'git@example.invalid: Permission denied (publickey).' >&2\nexit 255\n");
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
        git(host, "config", "core.sshCommand", ssh + " -i work_key");

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
        var git = lines.subList(quoted + 1, lines.size()).stream().map(String::strip).toList();
        assertTrue(git.contains("| ran: -o BatchMode=yes -i work_key askpass=force"), "ssh could not prompt: " + lines);
        assertTrue(git.contains("| git@example.invalid: Permission denied (publickey)."), "ssh's words are quoted: " + lines);
        // isx's note, apart from Git's words: what it added, and where the key is chosen.
        var note = String.join(" ", lines.subList(lines.indexOf(warning) + 1, quoted)).replaceAll("\\s+", " ");
        assertTrue(note.contains("isx added -o BatchMode=yes to your core.sshCommand, so ssh could not ask for a key's passphrase"), note);
        assertTrue(note.contains("choose another with core.sshCommand."), note);
    }
}
