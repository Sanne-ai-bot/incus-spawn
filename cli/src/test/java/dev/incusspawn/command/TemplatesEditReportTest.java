package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.Warnings;
import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(IsolatedHome.class)
class TemplatesEditReportTest {

    @Test
    void anIgnoredHostResourceKeyIsReportedOnceAfterAnEdit() throws Exception {
        // #1543: the edit flow loads every definition, then validates the edited file. The
        // load's warning about this file's ignored key is the report's, so it is not printed
        // twice, and the file is not called valid; a warning about another file still passes on.
        var images = Files.createDirectories(Environment.configDir().resolve("images"));
        var file = images.resolve("claude.yaml");
        Files.writeString(file, """
                name: tpl-claude-md
                image: images:fedora/44
                host-resources:
                  - source: ~/.claude/CLAUDE-isx.md
                    target: ~/.claude/CLAUDE.md
                """);
        Files.writeString(images.resolve("unnamed.yaml"), "image: images:fedora/44\n");
        var loadWarnings = new ArrayList<String>();
        var defs = ImageDef.loadAll(loadWarnings::add);
        var passedOn = new ArrayList<String>();
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();

        try (var ignored = Warnings.redirect(new Warnings.Channel(passedOn::add))) {
            TemplatesCommand.validateAndReport(file, defs, loadWarnings,
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        }

        assertEquals("  WARNING: unknown key 'host-resources[0].target', ignored; use 'path:' to set where it goes"
                + " in the instance"
                + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
        // The load also reads the working directory's project-local templates, so only these two are asserted.
        assertTrue(passedOn.contains("unnamed.yaml: no 'name:'; it is skipped, and isx build refuses until it is fixed"),
                passedOn.toString());
        assertFalse(passedOn.stream().anyMatch(w -> w.startsWith("claude.yaml: ")), passedOn.toString());
    }
}
