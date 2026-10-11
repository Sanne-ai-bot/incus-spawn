package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        assertEquals(List.of("a is deprecated."),
                ImageDef.deprecationWarnings(def("name: a\ndeprecated: TRUE\n"), Map.of()));
        assertFalse(def("name: a\n").isDeprecated());
    }

    @Test
    void buildingADescendantWarnsForEachDeprecatedAncestor() throws Exception {
        var root = def("name: tpl-root\n");
        var old = def("name: tpl-old\nparent: tpl-root\ndeprecated: use tpl-new instead\n");
        var older = def("name: tpl-older\nparent: tpl-old\ndeprecated: true\n");
        var mine = def("name: tpl-mine\nparent: tpl-older\n");
        var defs = Map.of("tpl-root", root, "tpl-old", old, "tpl-older", older, "tpl-mine", mine);

        assertEquals(List.of(
                "tpl-mine derives from tpl-old, which is deprecated: use tpl-new instead",
                "tpl-mine derives from tpl-older, which is deprecated."),
                ImageDef.deprecationWarnings(mine, defs));
        assertEquals(List.of("tpl-old is deprecated: use tpl-new instead"),
                ImageDef.deprecationWarnings(old, defs));
        assertEquals(List.of(), ImageDef.deprecationWarnings(root, defs));
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
        assertTrue(ImageDef.loadBuiltinByName("tpl-java").getDeprecated().contains("incus-spawn-templates"));
    }
}
