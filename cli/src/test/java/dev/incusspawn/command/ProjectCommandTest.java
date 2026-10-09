package dev.incusspawn.command;

import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectCommandTest {

    private static final String NAME = "proj";
    private static final String PARENT = "tpl-java";
    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAILED = new IncusClient.ExecResult(1, "", "");

    /** Runs what reaches the guest the way {@code su - agentuser -c} does: one bash command line in HOME. */
    private static IncusClient incus(Path home) {
        var incus = mock(IncusClient.class);
        when(incus.execInContainer(eq(NAME), eq("agentuser"), anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(2);
            var process = new ProcessBuilder("bash", "-c", script).directory(home.toFile())
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());
            return new IncusClient.ExecResult(process.waitFor(), "", output);
        });
        return incus;
    }

    @Test
    void aMultiWordPreBuildRunsWhole(@TempDir Path home) {
        assertTrue(ProjectCommand.preBuild(incus(home), NAME, "touch built 'with space'"));

        assertTrue(Files.exists(home.resolve("built")), "the pre-build ran at all");
        assertTrue(Files.exists(home.resolve("with space")), "the pre-build ran with all its arguments");
    }

    @Test
    void aFailedPreBuildIsReportedAsFailed(@TempDir Path home) {
        assertFalse(ProjectCommand.preBuild(incus(home), NAME, "echo starting && exit 3"));
    }

    /** A parent to create from, with every command run as agentuser answered by {@code guest}. */
    private static IncusClient parentWhoseGuestAnswers(IncusClient.ExecResult guest) {
        var incus = mock(IncusClient.class);
        when(incus.exists(PARENT)).thenReturn(true);
        when(incus.execInContainer(eq(NAME), eq("agentuser"), anyString())).thenReturn(guest);
        return incus;
    }

    private static ProjectConfig config(String preBuild, String... repos) {
        var config = new ProjectConfig();
        config.setName(NAME);
        config.setParent(PARENT);
        config.setRepos(List.of(repos));
        config.setPreBuild(preBuild);
        return config;
    }

    private static int create(IncusClient incus, ProjectConfig config) {
        var create = new ProjectCommand.Create();
        create.name = NAME;
        return create.create(incus, config);
    }

    @Test
    void createSucceedsAndTagsTheTemplate() {
        var incus = parentWhoseGuestAnswers(OK);

        assertEquals(0, create(incus, config("mvn install", "https://example.com/repo.git")));
        verify(incus).configSet(NAME, Metadata.PROJECT, NAME);
        verify(incus).stop(NAME);
        verify(incus, never()).delete(eq(NAME), anyBoolean());
    }

    @Test
    void aFailedCloneFailsCreateAndLeavesNoTemplate() {
        var incus = parentWhoseGuestAnswers(FAILED);

        assertEquals(1, create(incus, config(null, "https://example.com/repo.git")));
        verify(incus).delete(NAME, true);
        verify(incus, never()).configSet(NAME, Metadata.PROJECT, NAME);
    }

    @Test
    void aFailedPreBuildFailsCreateAndLeavesNoTemplate() {
        var incus = parentWhoseGuestAnswers(FAILED);

        assertEquals(1, create(incus, config("mvn install")));
        verify(incus).delete(NAME, true);
        verify(incus, never()).configSet(NAME, Metadata.PROJECT, NAME);
    }

    @Test
    void updateRefusesAConfigFoundForAnotherProject() {
        var incus = mock(IncusClient.class);
        when(incus.exists(NAME)).thenReturn(true);
        var other = config("rm -rf ~/proj");
        other.setName("other");
        var update = new ProjectCommand.Update();
        update.name = NAME;

        assertEquals(1, update.update(incus, other));
        verify(incus, never()).start(anyString());
        verifyNoMoreInteractions(ignoreStubs(incus));
    }

    @Test
    void anExceptionDuringCreateLeavesNoTemplate() {
        var incus = parentWhoseGuestAnswers(OK);
        doThrow(new IllegalStateException("timed out")).when(incus).waitForReady(eq(NAME), any());

        assertThrows(IllegalStateException.class, () -> create(incus, config(null)));
        verify(incus).deleteIfExists(NAME);
    }

    @Test
    void updateUsesAConfigNamedWithConfigWhateverItsName(@TempDir Path dir) {
        var incus = mock(IncusClient.class);
        when(incus.exists(NAME)).thenReturn(true);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.shellExec(NAME, "which", "npm")).thenReturn(FAILED);
        when(incus.execInContainer(eq(NAME), eq("agentuser"), anyString())).thenReturn(OK);
        var other = config("make");
        other.setName("other");
        var update = new ProjectCommand.Update();
        update.name = NAME;
        update.configPath = dir.resolve("incus-spawn.yaml");

        assertEquals(0, update.update(incus, other));
        verify(incus).execInContainer(NAME, "agentuser", "make");
    }

    @Test
    void aFailedStepShowsWhatItPrintedOnStdout() throws Exception {
        var output = InitStepOutputTest.capture(() -> assertFalse(GuestUpdate.finish(
                new IncusClient.ExecResult(1, "[ERROR] COMPILATION ERROR\n", ""), "pre-build")));

        assertTrue(output.err().contains("[ERROR] COMPILATION ERROR"), output.err());
    }
}
