package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateChangesTest {

    private static ImageDef def(String yaml) throws Exception {
        return ImageDef.parseYaml("name: tpl-app\nparent: tpl-dev\n" + yaml);
    }

    @Test
    void changedToolParametersAndRepoSettingsAreMarkedAsChanged() throws Exception {
        var built = def("""
                tools:
                  - jdk: {version: "17"}
                repos:
                  - url: https://github.com/acme/app.git
                """);
        var now = def("""
                tools:
                  - jdk: {version: "21"}
                repos:
                  - url: https://github.com/acme/app.git
                    branch: next
                """);
        assertEquals(List.of("~ tool jdk (version: 17) → jdk (version: 21)",
                        "~ repo https://github.com/acme/app.git → ~/app (branch (default) → next)"),
                TemplateChanges.definitionChanges(built, now));
    }

    @Test
    void settingsOutsidePackagesToolsAndReposListNothing() throws Exception {
        assertEquals(List.of(), TemplateChanges.definitionChanges(def("packages: [git]\ngui: false"),
                def("packages: [git]\ngui: true")));
    }
}
