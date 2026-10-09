package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateValidatorTest {

    private Map<String, ImageDef> knownTemplates() {
        return ImageDef.loadAll(List.of());
    }

    @Test
    void validTemplate(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                description: A test template
                parent: tpl-dev
                packages:
                  - htop
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void missingName(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                description: No name
                parent: tpl-dev
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("'name'")));
    }

    @Test
    void blankName(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: ""
                description: Blank name
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("'name'")));
    }

    @Test
    void invalidYaml(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                packages:
                  - valid
                  bad indentation here
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("test.yaml")));
    }

    @Test
    void missingTplPrefix(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-template
                description: No tpl- prefix
                parent: tpl-dev
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("tpl-")));
    }

    @Test
    void unknownParent(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-nonexistent
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("tpl-nonexistent")));
    }

    @Test
    void rootWithoutImage(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-bare
                image: ""
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("'image'")));
    }

    @Test
    void duplicateKeyIsError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                tools:
                  - graalvm
                tools:
                  - claude
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("duplicate key")));
    }

    @Test
    void errorsQuotingTheFileStayOnOneLine(@TempDir Path dir) throws Exception {
        // Definition text in an error must not start a line that reads as isx's own (#1133).
        var duplicate = dir.resolve("dup.yaml");
        Files.writeString(duplicate, """
                name: tpl-test
                "a\\nTemplate is valid.": 1
                "a\\nTemplate is valid.": 2
                """);
        var badPath = dir.resolve("nul.yaml");
        Files.writeString(badPath, """
                name: tpl-test
                parent: tpl-dev
                host-resources:
                  - source: /tmp
                    path: "/opt/x\\nTemplate is valid.\\0"
                """);
        for (var file : List.of(duplicate, badPath)) {
            var errors = TemplateValidator.validate(file, knownTemplates()).errors();
            assertEquals(1, errors.size(), errors.toString());
            assertEquals(1, errors.getFirst().lines().count(), errors.getFirst());
        }
    }

    @Test
    void unresolvableHostResourcesAreErrors(@TempDir Path dir) throws Exception {
        // A copy is never refused for where it goes, but isx build still refuses one it cannot
        // place, and a resource with nothing to mount or copy.
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                host-resources:
                  - source: https://example.com/x.tgz
                    mode: copy
                  - path: /opt/y
                """);
        var errors = TemplateValidator.validate(file, knownTemplates()).errors();
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("'path' is required for URL sources"), errors.getFirst());
        assertEquals("a host-resource has no 'source'", errors.get(1));
    }

    @Test
    void projectLocalTemplateReportsEachRefusalOnce(@TempDir Path dir) throws Exception {
        // collectEffective re-checks the targets validateHostResources already reported: neither
        // a forbidden mount nor a path Path.of refuses may escape as an exception, or twice. A
        // source is resolved against the project only there, so its refusal must still be reported.
        var images = Files.createDirectories(dir.resolve(".incus-spawn/images"));
        Files.createDirectories(dir.resolve("data"));
        var file = images.resolve("tpl-test.yaml");
        for (var resource : List.of("source: data\n    path: /etc/containers",
                "source: data\n    path: \"/opt/x\\0\"",
                "source: https://example.com/x.tgz\n    mode: copy",
                "source: \"data/x\\0\"\n    mode: copy",
                "source: \"data/x\\0\"\n    path: /opt/x\n    mode: copy")) {
            Files.writeString(file, """
                    name: tpl-test
                    parent: tpl-dev
                    host-resources:
                      - %s
                    """.formatted(resource));
            var errors = TemplateValidator.validate(file, knownTemplates(), images).errors();
            assertEquals(1, errors.size(), errors.toString());
        }
    }

    @Test
    void projectLocalTemplateReportsEveryRefusedResource(@TempDir Path dir) throws Exception {
        // One refused resource must not hide another's refusal, whichever rule refuses it.
        var images = Files.createDirectories(dir.resolve(".incus-spawn/images"));
        Files.createDirectories(dir.resolve("data"));
        var file = images.resolve("tpl-test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                host-resources:
                  - source: "data/x\\0"
                    path: /opt/x
                    mode: copy
                  - source: data
                    path: /etc/x
                  - source: ~/.ssh
                    path: /opt/ssh
                  - mode: copy
                    path: /opt/y
                """);
        var errors = TemplateValidator.validate(file, knownTemplates(), images).errors();
        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("Nul character not allowed"), errors.get(0));
        assertTrue(errors.get(1).contains("a system directory"), errors.get(1));
        assertTrue(errors.get(2).contains("host-resource '~/.ssh'"), errors.get(2));
        assertEquals("a host-resource has no 'source'", errors.get(3));
    }

    @Test
    void invalidHostResourceMode(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                host-resources:
                  - source: ~/.m2/repository
                    path: /home/user/.m2/repository
                    mode: readwrite
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("readwrite")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("readonly, overlay, copy")));
    }

    @Test
    void validHostResourceModes(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                host-resources:
                  - source: ~/.m2
                    path: /home/user/.m2
                    mode: readonly
                  - source: ~/.gradle
                    path: /home/user/.gradle
                    mode: overlay
                  - source: ~/.gitconfig
                    path: /home/user/.gitconfig
                    mode: copy
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void mountIntoASystemDirectoryIsAnError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                host-resources:
                  - source: ~/.config/containers
                    path: /etc/containers
                  - source: ~/storage.conf
                    path: /etc/containers/storage.conf
                    mode: copy
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertEquals(1, result.errors().size(), result.errors().toString());
        assertTrue(result.errors().get(0).contains("/etc/containers, a system directory"),
                result.errors().get(0));
    }

    @Test
    void duplicateToolsWarning(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                tools:
                  - maven-3
                  - podman
                  - maven-3
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("maven-3") && w.contains("more than once")));
    }

    @Test
    void unknownFieldsIgnored(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-test
                parent: tpl-dev
                some_future_field: value
                another_unknown: true
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void validRootTemplate(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: tpl-mybase
                description: Custom root
                image: images:ubuntu/24.04
                """);
        var result = TemplateValidator.validate(file, knownTemplates());
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }
}
