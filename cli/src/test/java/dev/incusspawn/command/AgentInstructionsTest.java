package dev.incusspawn.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link AgentInstructions#script} in a real shell against a scratch root, so the links,
 * the copy fallback and the cleanup are what a guest gets, not what the script text promises.
 * {@code chown} is a stub (the test is not root), which records what it was asked to do.
 */
class AgentInstructionsTest {

    private static final String CLAUDE = "/etc/claude-code/CLAUDE.md";
    private static final String PI = "/AGENTS.md";
    private static final String GEMINI = "/home/agentuser/.gemini/GEMINI.md";

    @TempDir
    Path root;
    @TempDir
    Path bin;

    @BeforeEach
    void stubs() throws IOException {
        stub("chown", "echo \"$@\" >> \"$(dirname \"$0\")/chown.log\"");
    }

    @Test
    void everySystemLocationIsALinkToTheOneStoredCopy() throws Exception {
        run("# incus-spawn environment\nv1", List.of(CLAUDE, PI));

        var store = at(AgentInstructions.STORE);
        assertEquals("# incus-spawn environment\nv1\n", Files.readString(store));
        assertTrue(Files.isSameFile(store, at(CLAUDE)));
        assertTrue(Files.isSameFile(store, at(PI)));
        assertEquals(3, Files.getAttribute(store, "unix:nlink"));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(store)));
    }

    @Test
    void aLocationInTheHomeIsTheUsersOwnCopy() throws Exception {
        run("# incus-spawn environment\nv1", List.of(PI, GEMINI));

        assertFalse(Files.isSameFile(at(AgentInstructions.STORE), at(GEMINI)));
        assertEquals(Files.readString(at(AgentInstructions.STORE)), Files.readString(at(GEMINI)));
        assertEquals("agentuser:agentuser " + at(GEMINI) + "\n", Files.readString(bin.resolve("chown.log")));
    }

    @Test
    void aRebuildRelinksEveryLocationToTheNewContent() throws Exception {
        run("# incus-spawn environment\nv1", List.of(CLAUDE, PI));
        // A tool that replaced its file broke the link; the next build makes it again.
        Files.delete(at(PI));
        Files.writeString(at(PI), "# incus-spawn environment\nedited");

        run("# incus-spawn environment\nv2", List.of(CLAUDE, PI));

        var store = at(AgentInstructions.STORE);
        assertTrue(Files.readString(store).endsWith("v2\n"));
        assertTrue(Files.isSameFile(store, at(CLAUDE)));
        assertTrue(Files.isSameFile(store, at(PI)));
        assertEquals(3, Files.getAttribute(store, "unix:nlink"));
    }

    @Test
    void aLinkThatCannotBeMadeFallsBackToACopy() throws Exception {
        stub("ln", "echo 'ln: Invalid cross-device link' >&2; exit 1");

        run("# incus-spawn environment\nv1", List.of(PI));

        assertFalse(Files.isSameFile(at(AgentInstructions.STORE), at(PI)));
        assertEquals("# incus-spawn environment\nv1\n", Files.readString(at(PI)));
    }

    @Test
    void aLocationNoToolDeclaresAnyMoreIsRemoved() throws Exception {
        run("# incus-spawn environment\nv1", List.of(CLAUDE, PI));

        run("# incus-spawn environment\nv2", List.of(PI));

        assertFalse(Files.exists(at(CLAUDE)), "claude is gone, so is its managed policy");
        assertTrue(Files.isSameFile(at(AgentInstructions.STORE), at(PI)));
    }

    @Test
    void anOlderBuildsManagedPolicyGoesWithoutClaude() throws Exception {
        // Written before the record existed, whether or not claude was installed.
        Files.createDirectories(at(CLAUDE).getParent());
        Files.writeString(at(CLAUDE), "# incus-spawn environment\nold\n");

        run("# incus-spawn environment\nv1", List.of(PI));

        assertFalse(Files.exists(at(CLAUDE)));
    }

    @Test
    void aFileATemplatePutThereItselfIsKept() throws Exception {
        Files.writeString(at(PI), "# my own instructions\n");

        run("# incus-spawn environment\nv1", List.of());

        assertEquals("# my own instructions\n", Files.readString(at(PI)));
    }

    @Test
    void aDirectoryAtALocationFailsTheBuild() throws Exception {
        Files.createDirectories(at(PI));

        var result = exec("# incus-spawn environment\nv1", List.of(PI));

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("/AGENTS.md is a directory"), result.output());
    }

    /** The MCP Codex runner reads its primer from the store, since the managed policy needs claude. */
    @Test
    void theCodexRunnerReadsTheStore() throws IOException {
        try (var in = AgentInstructions.class.getResourceAsStream("/mcp/codex-runner.cjs")) {
            assertNotNull(in);
            assertTrue(new String(in.readAllBytes()).contains("'" + AgentInstructions.STORE + "'"));
        }
    }

    private Path at(String path) {
        return root.resolve(path.substring(1));
    }

    private void stub(String name, String body) throws IOException {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        file.toFile().setExecutable(true);
    }

    private void run(String content, List<String> declared) throws Exception {
        var result = exec(content, declared);
        assertEquals(0, result.exitCode(), result.output());
    }

    private record Result(int exitCode, String output) {
    }

    private Result exec(String content, List<String> declared) throws Exception {
        var pb = new ProcessBuilder("sh", "-c", AgentInstructions.script(root.toString(), content, declared))
                .redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return new Result(process.exitValue(), output);
    }
}
