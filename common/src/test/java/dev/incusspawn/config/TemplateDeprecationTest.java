package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** {@code deprecated:} on a template, and the warnings a build of it or its descendants gets (#1195). */
class TemplateDeprecationTest {

    private static ImageDef def(String yaml) throws Exception {
        return ImageDef.parseYaml(yaml);
    }

    @Test
    void deprecatedTakesAMessageOrABoolean() throws Exception {
        assertEquals("use tpl-new", def("name: a\ndeprecated: use tpl-new\n").getDeprecated());
        assertTrue(def("name: a\ndeprecated: use tpl-new\n").isDeprecated());
        assertTrue(def("name: a\ndeprecated: true\n").isDeprecated());
        assertFalse(def("name: a\ndeprecated: false\n").isDeprecated());
        assertFalse(def("name: a\ndeprecated: False\n").isDeprecated());
        assertEquals(Optional.of("a is deprecated."),
                ImageDef.deprecationWarning(def("name: a\ndeprecated: TRUE\n"), Map.of()));
        assertFalse(def("name: a\n").isDeprecated());
    }

    @Test
    void onlyADirectlyDeprecatedParentWarns() throws Exception {
        var root = def("name: tpl-root\n");
        var old = def("name: tpl-old\nparent: tpl-root\ndeprecated: use tpl-new instead\n");
        var older = def("name: tpl-older\nparent: tpl-old\ndeprecated: true\n");
        var mine = def("name: tpl-mine\nparent: tpl-older\n");
        var grandchild = def("name: tpl-grandchild\nparent: tpl-mine\n");
        var defs = Map.of("tpl-root", root, "tpl-old", old, "tpl-older", older, "tpl-mine", mine,
                "tpl-grandchild", grandchild);

        // #1353: one warning, about the parent it names, not one per deprecated ancestor
        assertEquals(Optional.of("tpl-mine derives from tpl-older, which is deprecated."),
                ImageDef.deprecationWarning(mine, defs));
        // Further down, the template that names the deprecated one already said so
        assertEquals(Optional.empty(), ImageDef.deprecationWarning(grandchild, defs));
        // A deprecated template says only that it is, not also that its parent is
        assertEquals(Optional.of("tpl-older is deprecated."), ImageDef.deprecationWarning(older, defs));
        assertEquals(Optional.of("tpl-old is deprecated: use tpl-new instead"),
                ImageDef.deprecationWarning(old, defs));
        assertEquals(Optional.empty(), ImageDef.deprecationWarning(root, defs));
    }

    @Test
    void deprecatingATemplateDoesNotChangeItsFingerprint() throws Exception {
        var plain = def("name: a\nparent: tpl-root\npackages: [git]\n");
        var deprecated = def("name: a\nparent: tpl-root\npackages: [git]\ndeprecated: going away\n");
        assertEquals(plain.contentFingerprint(Map.of()), deprecated.contentFingerprint(Map.of()));
    }

    @Test
    void theBuiltInDevAndJavaTemplatesAreDeprecatedButMinimalIsNot() {
        assertTrue(ImageDef.loadBuiltinByName("tpl-dev").isDeprecated());
        assertTrue(ImageDef.loadBuiltinByName("tpl-java").isDeprecated());
        assertFalse(ImageDef.loadBuiltinByName("tpl-minimal").isDeprecated());
        for (var name : List.of("tpl-dev", "tpl-java")) {
            var note = ImageDef.loadBuiltinByName(name).getDeprecated();
            // #1353: your own template first, the maintained copy as a starting point
            assertTrue(note.contains("your own"), note);
            assertTrue(note.indexOf("your own") < note.indexOf("incus-spawn-templates"), note);
        }
    }
}
