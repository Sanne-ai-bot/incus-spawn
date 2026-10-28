package dev.incusspawn.command;

import dev.incusspawn.config.AgentContextGenerator;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs {@link AgentInstructions#script} in a real shell against a scratch root, so the links,
 * the copy fallback and the cleanup are what a guest gets, not what the script text promises.
 * {@code chown} is a stub (the test is not root), which records what it was asked to do.
 */
class AgentInstructionsTest {

    private static final String CLAUDE = "/etc/claude-code/CLAUDE.md";
    private static final String PI = "/AGENTS.md";
    private static final String GEMINI = "/home/agentuser/.gemini/GEMINI.md";
    private static final String CODEX = "/etc/codex/requirements.toml";

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
        run("# incus-spawn environment\nv1", linked(CLAUDE, PI));

        var store = at(AgentInstructions.STORE);
        assertEquals("# incus-spawn environment\nv1\n", Files.readString(store));
        assertTrue(Files.isSameFile(store, at(CLAUDE)));
        assertTrue(Files.isSameFile(store, at(PI)));
        assertEquals(3, Files.getAttribute(store, "unix:nlink"));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(store)));
    }

    @Test
    void aLocationInTheHomeIsTheUsersOwnCopy() throws Exception {
        run("# incus-spawn environment\nv1", linked(PI, GEMINI));

        assertFalse(Files.isSameFile(at(AgentInstructions.STORE), at(GEMINI)));
        assertEquals(Files.readString(at(AgentInstructions.STORE)), Files.readString(at(GEMINI)));
        assertEquals("agentuser:agentuser " + at(GEMINI) + "\n", Files.readString(bin.resolve("chown.log")));
    }

    @Test
    void aRebuildRelinksEveryLocationToTheNewContent() throws Exception {
        run("# incus-spawn environment\nv1", linked(CLAUDE, PI));
        // A tool that replaced its file broke the link; the next build makes it again.
        Files.delete(at(PI));
        Files.writeString(at(PI), "# incus-spawn environment\nedited");

        run("# incus-spawn environment\nv2", linked(CLAUDE, PI));

        var store = at(AgentInstructions.STORE);
        assertTrue(Files.readString(store).endsWith("v2\n"));
        assertTrue(Files.isSameFile(store, at(CLAUDE)));
        assertTrue(Files.isSameFile(store, at(PI)));
        assertEquals(3, Files.getAttribute(store, "unix:nlink"));
    }

    @Test
    void aLinkThatCannotBeMadeFallsBackToACopy() throws Exception {
        stub("ln", "echo 'ln: Invalid cross-device link' >&2; exit 1");

        run("# incus-spawn environment\nv1", linked(PI));

        assertFalse(Files.isSameFile(at(AgentInstructions.STORE), at(PI)));
        assertEquals("# incus-spawn environment\nv1\n", Files.readString(at(PI)));
    }

    @Test
    void aLocationNoToolDeclaresAnyMoreIsRemoved() throws Exception {
        run("# incus-spawn environment\nv1", linked(CLAUDE, PI));

        run("# incus-spawn environment\nv2", linked(PI));

        assertFalse(Files.exists(at(CLAUDE)), "claude is gone, so is its managed policy");
        assertTrue(Files.isSameFile(at(AgentInstructions.STORE), at(PI)));
    }

    @Test
    void anOlderBuildsManagedPolicyGoesWithoutClaude() throws Exception {
        // Written before the record existed, whether or not claude was installed.
        Files.createDirectories(at(CLAUDE).getParent());
        Files.writeString(at(CLAUDE), "# incus-spawn environment\nold\n");

        run("# incus-spawn environment\nv1", linked(PI));

        assertFalse(Files.exists(at(CLAUDE)));
    }

    @Test
    void aFileATemplatePutThereItselfIsKept() throws Exception {
        Files.writeString(at(PI), "# my own instructions\n");

        run("# incus-spawn environment\nv1", linked());

        assertEquals("# my own instructions\n", Files.readString(at(PI)));
    }

    @Test
    void aDirectoryAtALocationFailsTheBuild() throws Exception {
        Files.createDirectories(at(PI));

        var result = exec("# incus-spawn environment\nv1", linked(PI));

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("/AGENTS.md is a directory"), result.output());
    }

    @Test
    void aRenderedLocationIsItsOwnFileAndARebuildReplacesIt() throws Exception {
        run("# incus-spawn environment\nv1", List.of(codex("x = 1")));
        assertEquals("# incus-spawn environment\nx = 1\n", Files.readString(at(CODEX)));
        assertFalse(Files.isSameFile(at(AgentInstructions.STORE), at(CODEX)));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(at(CODEX))));

        var output = run("# incus-spawn environment\nv2", List.of(codex("x = 2")));

        assertEquals("# incus-spawn environment\nx = 2\n", Files.readString(at(CODEX)));
        assertFalse(output.contains(AgentInstructions.KEPT), output);
        try (var dir = Files.list(at(CODEX).getParent())) {
            assertEquals(List.of(at(CODEX)), dir.toList(), "no temporary file left behind");
        }
    }

    /** Codex's requirements may hold a template's own policy, which isx never overwrites. */
    @Test
    void aRenderedFileATemplateWroteIsKeptAndReported() throws Exception {
        Files.createDirectories(at(CODEX).getParent());
        Files.writeString(at(CODEX), "allowed_sandbox_modes = [\"read-only\"]\n");

        var output = run("# incus-spawn environment\nv1", List.of(codex("x = 1")));

        assertEquals("allowed_sandbox_modes = [\"read-only\"]\n", Files.readString(at(CODEX)));
        assertEquals(AgentInstructions.KEPT + CODEX + "\n", output);
    }

    @Test
    void anEmptyRenderedFileIsReplaced() throws Exception {
        Files.createDirectories(at(CODEX).getParent());
        Files.writeString(at(CODEX), "");

        var output = run("# incus-spawn environment\nv1", List.of(codex("x = 1")));

        assertEquals("# incus-spawn environment\nx = 1\n", Files.readString(at(CODEX)));
        assertEquals("", output);
    }

    /** What the script reports kept becomes the build's warning, one per path. */
    @Test
    void aKeptFileIsABuildWarning() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString()))
                .thenReturn(new IncusClient.ExecResult(0, AgentInstructions.KEPT + CODEX + "\n", ""));

        var output = RecordingOutput.during(() -> AgentInstructions.install(new Container(incus, "test"),
                "# incus-spawn environment\nv1", List.of(codex("x = 1"))));

        var warnings = output.events().stream().filter(e -> e.kind() == RecordingOutput.Kind.WARN).toList();
        assertEquals(1, warnings.size(), output.events().toString());
        assertTrue(warnings.getFirst().msg().startsWith("Kept " + CODEX + ", which the template wrote"), warnings.toString());
    }

    @Test
    void aRenderedLocationNoToolDeclaresAnyMoreIsRemoved() throws Exception {
        run("# incus-spawn environment\nv1", List.of(codex("x = 1")));

        run("# incus-spawn environment\nv2", linked());

        assertFalse(Files.exists(at(CODEX)));
    }

    @Test
    void aRenderedFileMustStartWithTheHeading() {
        assertThrows(IllegalArgumentException.class, () -> new AgentInstructions.Location(CODEX, "x = 1"));
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

    private static AgentInstructions.Location codex(String toml) {
        return new AgentInstructions.Location(CODEX, AgentContextGenerator.HEADING + "\n" + toml);
    }

    /** Locations that are the stored copy itself, linked or copied. */
    private static List<AgentInstructions.Location> linked(String... paths) {
        return Arrays.stream(paths).map(path -> new AgentInstructions.Location(path, null)).toList();
    }

    private String run(String content, List<AgentInstructions.Location> declared) throws Exception {
        var result = exec(content, declared);
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }

    private record Result(int exitCode, String output) {
    }

    private Result exec(String content, List<AgentInstructions.Location> declared) throws Exception {
        var pb = new ProcessBuilder("sh", "-c", AgentInstructions.script(root.toString(), content, declared))
                .redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return new Result(process.exitValue(), output);
    }
}
