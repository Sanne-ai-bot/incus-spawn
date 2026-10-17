package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** #1505: a test's Git login never reaches a credential helper of the developer's. */
class HermeticGitTest {
    @TempDir Path root;

    /** A helper Git reads before the test config, as Apple's Git reads one despite GIT_CONFIG_SYSTEM. */
    private Path leakyHelper() throws Exception {
        var system = root.resolve("system-gitconfig");
        Files.writeString(system, "[credential]\n\thelper = \"!f() { cat >> '" + root.resolve("leaked") + "'; }; f\"\n");
        return system;
    }

    @Test
    void anApprovedLoginNeverReachesAHelperBelowTheTestConfig() throws Exception {
        var pb = HermeticGit.configure(new ProcessBuilder("git", "credential", "approve").directory(root.toFile()),
                root.resolve("gitconfig"));
        pb.environment().remove("GIT_CONFIG_NOSYSTEM");
        pb.environment().put("GIT_CONFIG_SYSTEM", leakyHelper().toString());
        var process = pb.redirectErrorStream(true).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write("protocol=https\nhost=github.com\nusername=x-access-token\npassword=gho_isx_test\n\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        assertFalse(Files.exists(root.resolve("leaked")), "the proof was stored by a helper the test did not install");
    }

    /** A repository whose own config, read after the test's, holds {@code helper}. */
    private Path repositoryWith(String helper) throws Exception {
        var repo = Files.createDirectory(root.resolve("repo"));
        var init = new ProcessBuilder("git", "init", "-q", repo.toString()).redirectErrorStream(true).start();
        assertEquals(0, init.waitFor(), new String(init.getInputStream().readAllBytes()));
        Files.writeString(repo.resolve(".git/config"), helper, StandardOpenOption.APPEND);
        return repo;
    }

    @Test
    void aHelperForEveryHostFailsTheCheck() throws Exception {
        var repo = repositoryWith(Files.readString(leakyHelper()));
        assertThrows(AssertionFailedError.class,
                () -> HermeticGit.configure(new ProcessBuilder("git").directory(repo.toFile()), root.resolve("gitconfig")));
    }

    @Test
    void aStrayHelperForGitHubFailsTheCheck() throws Exception {
        var repo = repositoryWith("[credential \"https://github.com\"]\n\thelper = !gh auth git-credential\n");
        assertThrows(AssertionFailedError.class,
                () -> HermeticGit.configure(new ProcessBuilder("git").directory(repo.toFile()), root.resolve("gitconfig")));
    }
}
