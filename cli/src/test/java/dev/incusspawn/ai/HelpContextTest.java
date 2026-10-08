package dev.incusspawn.ai;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HelpContextTest {

    @Test
    void imageYamlRedactsTokenField() {
        var yaml = """
                name: my-tool
                proxy:
                  auth:
                    token: ghp_abcdef1234567890
                """;
        var sanitized = HelpContext.sanitizeImageYaml(yaml);
        assertFalse(sanitized.contains("ghp_abcdef1234567890"));
        assertTrue(sanitized.contains("<redacted>"));
    }

    @Test
    void imageYamlPreservesTemplateReferences() {
        var yaml = """
                name: my-tool
                proxy:
                  auth:
                    token: "${api-key}"
                """;
        var sanitized = HelpContext.sanitizeImageYaml(yaml);
        assertTrue(sanitized.contains("${api-key}"));
        assertFalse(sanitized.contains("<redacted>"));
    }

    @Test
    void imageYamlRedactsShortSecrets() {
        var yaml = "  password: short1234\n";
        var sanitized = HelpContext.sanitizeImageYaml(yaml);
        assertFalse(sanitized.contains("short1234"));
    }

    @Test
    void imageYamlPreservesKeyOnlyLines() {
        var yaml = "  secret:\n    nested: value";
        var sanitized = HelpContext.sanitizeImageYaml(yaml);
        assertEquals(yaml, sanitized);
    }

    @Test
    void toolYamlRedactsSecretConfigValue(@TempDir Path dir) throws IOException {
        var yaml = """
                name: my-tool
                proxy:
                  configuration:
                    api-key:
                      value: "ghp_realSecretTokenHere123"
                      secret: true
                  auth:
                    - domains:
                        - api.example.com
                      type: bearer
                      token: "${api-key}"
                """;
        var file = dir.resolve("my-tool.yaml");
        Files.writeString(file, yaml);

        var sanitized = HelpContext.sanitizeToolYaml(file);
        assertFalse(sanitized.contains("ghp_realSecretTokenHere123"),
                "secret ConfigEntry value must be redacted");
        assertTrue(sanitized.contains("<redacted>"));
        assertTrue(sanitized.contains("${api-key}"),
                "template references must be preserved");
    }

    @Test
    void toolYamlRedactsLiteralAuthCredentials(@TempDir Path dir) throws IOException {
        var yaml = """
                name: my-tool
                proxy:
                  configuration:
                    user:
                      value: "deploy-bot"
                  auth:
                    - domains:
                        - api.example.com
                      type: basic
                      username: "deploy-bot"
                      password: "supersecretpassword123"
                """;
        var file = dir.resolve("my-tool.yaml");
        Files.writeString(file, yaml);

        var sanitized = HelpContext.sanitizeToolYaml(file);
        assertFalse(sanitized.contains("supersecretpassword123"),
                "literal password in AuthDef must be redacted");
    }

    @Test
    void toolYamlPreservesTemplateAuthFields(@TempDir Path dir) throws IOException {
        var yaml = """
                name: my-tool
                proxy:
                  configuration:
                    token:
                      config-path: "myService.token"
                      secret: true
                  auth:
                    - domains:
                        - api.example.com
                      type: bearer
                      token: "${token}"
                """;
        var file = dir.resolve("my-tool.yaml");
        Files.writeString(file, yaml);

        var sanitized = HelpContext.sanitizeToolYaml(file);
        assertTrue(sanitized.contains("${token}"),
                "template references in auth must be preserved");
        assertFalse(sanitized.contains("<redacted>"),
                "nothing should be redacted when all credentials use templates");
    }

    @Test
    void toolYamlWithNoProxyPassesThrough(@TempDir Path dir) throws IOException {
        var yaml = """
                name: simple-tool
                packages:
                  - curl
                run:
                  - echo hello
                """;
        var file = dir.resolve("simple-tool.yaml");
        Files.writeString(file, yaml);

        var sanitized = HelpContext.sanitizeToolYaml(file);
        assertEquals(yaml, sanitized);
    }

    @Test
    void definitionsListToolsOfATildeSearchPath(@TempDir Path home) throws IOException {
        var realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var tools = Files.createDirectories(home.resolve("my-templates/tools"));
            Files.writeString(tools.resolve("tilde-marker.yaml"), "name: tilde-marker\n");
            var config = new SpawnConfig();
            config.setSearchPaths(List.of("~/my-templates"));
            config.save();

            assertTrue(HelpContext.definitions().contains("# tilde-marker.yaml (user-defined)"),
                    "a search path written as ~/... must contribute its tools");
        } finally {
            if (realHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", realHome);
        }
    }
}
