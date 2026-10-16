package dev.incusspawn.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class GitTestUtils {

    private GitTestUtils() {}

    public static String runGit(Path dir, String... args) throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        // Commits and fetches start auto maintenance (older Git: gc) detached: it could outlive the
        // test and write into the repository while @TempDir deletes it (#1473).
        cmd.addAll(List.of("git", "-c", "gc.auto=0", "-c", "maintenance.auto=false"));
        cmd.addAll(List.of(args));
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            assertEquals(0, exit, "git " + String.join(" ", args) + " failed in " + dir + ": " + output);
            return output;
        } catch (InterruptedException e) {
            try {
                process.destroyForcibly();
            }
            catch (RuntimeException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }

    /** {@link #runGit}'s output, stripped. */
    public static String git(Path dir, String... args) throws IOException, InterruptedException {
        return runGit(dir, args).strip();
    }

    /**
     * A repository that publishes binaries as commits, like a release repository: five commits on
     * {@code main} each replacing {@code isx.deb}, and one more on a {@code stable} branch.
     */
    public static Path releasesFixture(Path parent) throws IOException, InterruptedException {
        var repo = parent.resolve("releases");
        runGit(parent, "init", "-q", "-b", "main", repo.toString());
        for (int i = 1; i <= 5; i++) commitBinary(repo, "release " + i);
        runGit(repo, "checkout", "-q", "-b", "stable");
        commitBinary(repo, "stable release");
        runGit(repo, "checkout", "-q", "main");
        return repo;
    }

    /** Commit a new random {@code isx.deb}, seeded by {@code message}. */
    public static void commitBinary(Path repo, String message) throws IOException, InterruptedException {
        var bytes = new byte[64 * 1024];
        new java.util.Random(message.hashCode()).nextBytes(bytes);
        Files.write(repo.resolve("isx.deb"), bytes);
        runGit(repo, "add", "isx.deb");
        runGit(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", message);
    }
}
