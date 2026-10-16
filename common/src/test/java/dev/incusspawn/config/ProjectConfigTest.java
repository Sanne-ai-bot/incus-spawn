package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectConfigTest {

    @Test
    void anEmptyRepoItemIsDropped(@TempDir Path dir) throws Exception {
        // A bare '-' arrives as null, which the project build would clone as 'null'
        var file = Files.writeString(dir.resolve("incus-spawn.yaml"), """
                name: p
                repos:
                  -
                  - https://github.com/example/repo.git
                """);
        assertEquals(List.of("https://github.com/example/repo.git"), Jjq.projectConfig(file).getRepos());
    }
}
