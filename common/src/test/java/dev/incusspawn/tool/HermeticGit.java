package dev.incusspawn.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs Git against one test config and no credential helper of the developer's (#1505).
 *
 * <p>{@code GIT_CONFIG_SYSTEM} alone is not enough: Apple's Command Line Tools Git also reads
 * {@code credential.helper osxkeychain} from its own install prefix, so a test's successful HTTPS
 * login was approved into the login keychain, and later tests read that proof back. Here Git reads no
 * system config, and its global config is a file next to the test's that empties the helper list
 * collected so far (an empty {@code credential.helper}), then includes the test's config unchanged.
 */
public final class HermeticGit {
    static final String RESET = "[credential]\n\thelper =\n";
    /** A host no test configures, and the one the tests log in to. */
    static final List<String> PROBES = List.of("https://isx-hermetic.invalid/repo", "https://github.com/isx/hermetic.git");
    private static final Set<Path> CHECKED = ConcurrentHashMap.newKeySet();

    private HermeticGit() {}

    /**
     * Points {@code pb} at {@code config} alone, with none of the test's own Git or GitHub environment
     * and no prompt, then checks that Git would ask no helper from outside {@code config}.
     */
    public static ProcessBuilder configure(ProcessBuilder pb, Path config) throws IOException, InterruptedException {
        var global = config.toAbsolutePath().resolveSibling(config.getFileName() + ".hermetic");
        var include = config.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(global, RESET + "[include]\n\tpath = \"" + include + "\"\n");
        environment(pb, global);
        var dir = pb.directory() != null ? pb.directory().toPath() : Path.of("").toAbsolutePath();
        if (!CHECKED.contains(dir)) {
            assertNoStrayHelper(dir);
            CHECKED.add(dir);
        }
        return pb;
    }

    private static void environment(ProcessBuilder pb, Path global) {
        var env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("GIT_") || k.equals("GH_TOKEN") || k.equals("SSH_ASKPASS"));
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", global.toString());
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "/bin/false");
    }

    /**
     * Fails if Git, run in {@code dir} with a global config holding only the reset, runs any credential
     * helper for a {@link #PROBES} host: one from a level Git reads after the reset, such as the repository's.
     */
    private static void assertNoStrayHelper(Path dir) throws IOException, InterruptedException {
        var global = Files.createTempFile("hermetic-git", ".gitconfig");
        try {
            Files.writeString(global, RESET);
            for (var url : PROBES) assertNoStrayHelper(dir, global, url);
        } finally {
            Files.delete(global);
        }
    }

    private static void assertNoStrayHelper(Path dir, Path global, String url) throws IOException, InterruptedException {
        var trace = Files.createTempFile("hermetic-git", ".trace");
        var pb = new ProcessBuilder("git", "credential", "fill").directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(trace.toFile());
        environment(pb, global);
        // An empty askpass ends Git's askpass chain (core.askPass, SSH_ASKPASS), so the only commands
        // Git can run for this host are credential helpers.
        pb.environment().put("GIT_ASKPASS", "");
        pb.environment().put("GIT_TRACE", "1");
        var process = pb.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(("url=" + url + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Git must never wait for a terminal prompt");
            var output = Files.readString(trace);
            assertFalse(output.contains("run_command:"),
                    "a credential helper the test did not install is in effect for " + url + ":\n" + output);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Files.delete(trace);
        }
    }
}
