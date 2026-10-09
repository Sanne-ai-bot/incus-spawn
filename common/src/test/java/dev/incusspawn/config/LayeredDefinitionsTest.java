package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LayeredDefinitionsTest {

    private static void scan(LayeredDefinitions<String> defs, Path file) {
        defs.beginDirectory();
        defs.put("tpl-x", file.toString(), file);
        defs.endDirectory();
    }

    @Test
    void aFileThatOverrodeOnTwoLayersAnswersTheLatestItReplaced(@TempDir Path dir) {
        var user = dir.resolve("user/x.yaml");
        var search = dir.resolve("search/x.yaml");
        var defs = new LayeredDefinitions<String>("image");
        defs.putBuiltin("tpl-x", "built-in");
        scan(defs, user);
        scan(defs, search);
        scan(defs, user); // a search path naming the user directory again

        assertEquals(user.toString(), defs.getSource("tpl-x"));
        assertEquals(search.toString(), defs.overriddenSource("tpl-x"));
    }

    @Test
    void aDirectoryReachedThroughASymlinkOverridesNothing(@TempDir Path dir) throws Exception {
        var real = Files.createDirectories(dir.resolve("images"));
        var file = Files.writeString(real.resolve("x.yaml"), "name: tpl-x\n");
        var link = Files.createSymbolicLink(dir.resolve("linked"), real);
        var defs = new LayeredDefinitions<String>("image");
        defs.putBuiltin("tpl-x", "built-in");
        scan(defs, file);
        scan(defs, link.resolve("x.yaml"));

        assertEquals(link.resolve("x.yaml").toString(), defs.getSource("tpl-x"));
        assertEquals("built-in", defs.overriddenSource("tpl-x"));
        assertEquals(1, defs.overrides().size());
    }

    @Test
    void theSameFileOnALaterLayerStillGivesThatLayersDefinition(@TempDir Path dir) {
        // A project's .incus-spawn that is also a search path: the project layer's definition,
        // confined to the project, must be the one kept
        var file = dir.resolve("images/x.yaml");
        var defs = new LayeredDefinitions<String>("image");
        defs.beginDirectory();
        defs.put("tpl-x", "from the search path", file);
        defs.endDirectory();
        defs.beginDirectory();
        defs.put("tpl-x", "from the project, confined", file);
        defs.endDirectory();

        assertEquals("from the project, confined", defs.defs().get("tpl-x"));
        assertTrue(defs.overrides().isEmpty());
    }
}
