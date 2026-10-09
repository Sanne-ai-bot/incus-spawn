package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectCommandTest {

    private static final String NAME = "proj";

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
}
