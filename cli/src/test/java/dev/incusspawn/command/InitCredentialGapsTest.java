package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.util.BuildOutput.stripAnsi;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Init's closing box names each credential a template still lacks and what {@code isx branch}
 * will refuse without it, so a skipped or unverified credential is not left to the first branch
 * (#1438).
 */
class InitCredentialGapsTest {

    private static Map<String, ImageDef> defs(String... yamls) throws Exception {
        var defs = new LinkedHashMap<String, ImageDef>();
        for (var yaml : yamls) {
            var def = ImageDef.parseYaml(yaml);
            defs.put(def.getName(), def);
        }
        return defs;
    }

    private static List<String> lines(Map<String, List<String>> missing, Map<String, ImageDef> defs) {
        return InitCommand.credentialGapLines(missing, defs).stream().map(l -> stripAnsi(l).strip()).toList();
    }

    @Test
    void nothingMissingAddsNothing() {
        assertEquals(List.of(), InitCommand.credentialGapLines(Map.of(), Map.of()));
    }

    @Test
    void onlyTheTopmostTemplateIsNamed() throws Exception {
        var defs = defs("name: tpl-minimal\n", "name: tpl-dev\nparent: tpl-minimal\n",
                "name: tpl-agent\nparent: tpl-dev\n", "name: tpl-isx\nparent: tpl-dev\n");
        var missing = new LinkedHashMap<String, List<String>>();
        missing.put("GitHub personal access token", List.of("tpl-dev", "tpl-agent", "tpl-isx"));
        assertEquals(List.of("", "Not configured:", "• GitHub personal access token",
                        "'isx branch' refuses tpl-dev and 2 templates built on", "it.",
                        "Re-run 'isx init' to configure it."),
                lines(missing, defs));
    }

    /** A child that does not need it (another account, say) is not counted as refused. */
    @Test
    void onlyTheTemplatesThatNeedItAreCounted() throws Exception {
        var defs = defs("name: tpl-minimal\n", "name: tpl-dev\nparent: tpl-minimal\n",
                "name: tpl-agent\nparent: tpl-dev\n", "name: tpl-isx\nparent: tpl-dev\n");
        var missing = Map.of("GitHub personal access token", List.of("tpl-dev", "tpl-isx"));
        assertTrue(lines(missing, defs).contains("'isx branch' refuses tpl-dev and 1 template built on it."));
    }

    /** A root template has no parent to look for, whatever list type a caller passes. */
    @Test
    void aRootTemplateThatNeedsItIsNamed() throws Exception {
        var defs = defs("name: tpl-minimal\n", "name: tpl-dev\nparent: tpl-minimal\n");
        var missing = Map.of("GitHub personal access token", List.of("tpl-minimal", "tpl-dev"));
        var text = String.join(" ", lines(missing, defs));
        assertTrue(text.contains("'isx branch' refuses tpl-minimal and 1 template built on it."), text);
    }

    /** A deprecated template between two that need it does not make the lower one topmost. */
    @Test
    void aTemplateBelowANamedAncestorIsCountedNotNamed() throws Exception {
        var defs = defs("name: tpl-a\n", "name: tpl-b\nparent: tpl-a\n", "name: tpl-c\nparent: tpl-b\n");
        var missing = Map.of("GitHub personal access token", List.of("tpl-a", "tpl-c"));
        var text = String.join(" ", lines(missing, defs));
        assertTrue(text.contains("'isx branch' refuses tpl-a and 1 template built on it."), text);
    }

    @Test
    void unrelatedTemplatesAreEachNamedAndLongLinesFitTheBox() throws Exception {
        var defs = defs("name: tpl-minimal\n", "name: tpl-first-long-name\nparent: tpl-minimal\n",
                "name: tpl-second-long-name\nparent: tpl-minimal\n", "name: tpl-third-long-name\nparent: tpl-minimal\n");
        var missing = new LinkedHashMap<String, List<String>>();
        missing.put("OpenAI API key", List.of("tpl-first-long-name", "tpl-second-long-name", "tpl-third-long-name"));
        missing.put("GitHub personal access token", List.of("tpl-first-long-name"));
        missing.put("A credential whose description is far too long to fit on one line of the box",
                List.of("tpl-first-long-name"));
        var raw = InitCommand.credentialGapLines(missing, defs);
        for (var line : raw) assertTrue(stripAnsi(line).length() <= 62, line);
        var text = String.join(" ", lines(missing, defs));
        assertTrue(text.contains("'isx branch' refuses tpl-first-long-name, tpl-second-long-name and "
                + "tpl-third-long-name."), text);
        assertTrue(text.contains("• GitHub personal access token 'isx branch' refuses tpl-first-long-name."), text);
        assertTrue(text.endsWith("Re-run 'isx init' to configure them."), text);
    }
}
