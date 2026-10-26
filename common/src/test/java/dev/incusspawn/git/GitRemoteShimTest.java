package dev.incusspawn.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the {@code git-remote-isx} shim as git does, with a stub {@code isx} on {@code PATH} that
 * records its arguments: the shim is the parser every fetch and push goes through (#1709).
 */
class GitRemoteShimTest {

    private static final Path SHIM = shim();

    @TempDir
    Path tmp;

    record Result(int exit, String stdout, List<String> isxArgs) {}

    @Test
    void anAbsolutePathAfterADoubleSlashReachesTheHelperWithOneSlash() throws Exception {
        var result = run("isx://inst//home/agentuser/p");

        assertEquals(0, result.exit());
        assertEquals("connect\n\n\n", result.stdout());
        assertEquals(List.of("git-remote-helper", "inst", "git-upload-pack", "/home/agentuser/p"), result.isxArgs());
    }

    @Test
    void aHomeRelativePathAfterADoubleSlashIsLeftForTheHelperToExpand() throws Exception {
        assertEquals(List.of("git-remote-helper", "inst", "git-upload-pack", "/~/repo"),
                run("isx://inst//~/repo").isxArgs());
    }

    @Test
    void aPushReachesTheHelperWithItsOwnService() throws Exception {
        assertEquals(List.of("git-remote-helper", "inst", "git-receive-pack", "/home/agentuser/p"),
                run("isx://inst//home/agentuser/p", "git-receive-pack").isxArgs());
    }

    @Test
    void anOldStyleSingleSlashUrlStillNamesAnAbsolutePath() throws Exception {
        assertEquals(List.of("git-remote-helper", "inst", "git-upload-pack", "/home/agentuser/p"),
                run("isx://inst/home/agentuser/p").isxArgs());
    }

    @Test
    void aHomeRelativePathIsLeftForTheHelperToExpand() throws Exception {
        assertEquals(List.of("git-remote-helper", "inst", "git-upload-pack", "/~/repo"),
                run("isx://inst/~/repo").isxArgs());
    }

    @Test
    void aUrlOfAnotherSchemeIsRefused() throws Exception {
        var result = run("https://github.com/owner/repo");

        assertEquals(1, result.exit());
        assertNull(result.isxArgs());
    }

    private static Path shim() {
        try {
            return Path.of(GitRemoteShimTest.class.getResource("/git-remote-isx").toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private Result run(String url) throws IOException, InterruptedException {
        return run(url, "git-upload-pack");
    }

    private Result run(String url, String service) throws IOException, InterruptedException {
        var bin = Files.createDirectories(tmp.resolve("bin"));
        var argsFile = tmp.resolve("isx-args");
        var isx = Files.writeString(bin.resolve("isx"), """
                #!/bin/sh
                printf '%%s\\n' "$@" > '%s'
                """.formatted(argsFile));
        Files.setPosixFilePermissions(isx, PosixFilePermissions.fromString("rwxr-xr-x"));

        var pb = new ProcessBuilder("bash", SHIM.toString(), "origin", url).redirectErrorStream(false);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var process = pb.start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(("capabilities\nconnect " + service + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException refusedBeforeReading) {
            // A shim that refuses the URL exits without reading git's lines.
        }
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the shim did not exit");
        var isxArgs = Files.exists(argsFile) ? Files.readAllLines(argsFile) : null;
        return new Result(process.exitValue(), stdout, isxArgs);
    }
}
