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
                TemplateChanges.definitionChanges(built, now, java.util.Map.of()));
    }

    @Test
    void aChangedRepoDepthIsMarkedAsChanged() throws Exception {
        var built = def("""
                repos:
                  - url: https://github.com/acme/releases.git
                """);
        var now = def("""
                repos:
                  - url: https://github.com/acme/releases.git
                    depth: 1
                """);
        assertEquals(List.of("~ repo https://github.com/acme/releases.git → ~/releases (depth (full) → 1)"),
                TemplateChanges.definitionChanges(built, now, java.util.Map.of()));
    }

    @Test
    void aChangedMaskIsListed() throws Exception {
        assertEquals(List.of("+ masked unit tmp.mount"), TemplateChanges.definitionChanges(def("packages: [git]"),
                def("packages: [git]\nmask_services: [tmp.mount]"), java.util.Map.of()));
    }

    @Test
    void aChangedPackageToRemoveIsListed() throws Exception {
        assertEquals(List.of("+ removed package nano"), TemplateChanges.definitionChanges(def("packages: [git]"),
                def("packages: [git]\nremove_packages: [nano]"), java.util.Map.of()));
    }

    @Test
    void settingsOutsidePackagesMasksRemovedPackagesToolsAndReposListNothing() throws Exception {
        assertEquals(List.of(), TemplateChanges.definitionChanges(def("packages: [git]\ngui: false"),
                def("packages: [git]\ngui: true"), java.util.Map.of()));
    }

    @Test
    void aToolTheLeafDroppedButAParentProvidesSaysItIsStillInherited() throws Exception {
        var dev = ImageDef.parseYaml("name: tpl-dev\nimage: x\ntools: [gh]");
        var built = def("tools: [gh, claude]");
        var now = def("tools: [claude]");
        assertEquals(List.of("− tool gh (still inherited from tpl-dev)"),
                TemplateChanges.definitionChanges(built, now, java.util.Map.of("tpl-dev", dev, "tpl-app", now)));
    }

    @Test
    void aPrimeChangeShowsBeforeAndAfter() throws Exception {
        var built = def("repos:\n  - url: https://github.com/a/b.git\n    prime: mvn a");
        var now = def("repos:\n  - url: https://github.com/a/b.git\n    prime: mvn b");
        assertEquals(List.of("~ repo https://github.com/a/b.git → ~/b (prime mvn a → mvn b)"),
                TemplateChanges.definitionChanges(built, now, java.util.Map.of()));
    }

    @Test
    void aPrimeWrittenAsAListIsTheSameCommandsAsWritten() throws Exception {
        var built = def("repos:\n  - url: https://github.com/a/b.git\n    prime: mise install");
        var same = def("repos:\n  - url: https://github.com/a/b.git\n    prime: [mise install]");
        var more = def("repos:\n  - url: https://github.com/a/b.git\n    prime: [mise install, gradle deps]");
        assertEquals(List.of(), TemplateChanges.definitionChanges(built, same, java.util.Map.of()));
        assertEquals(List.of("~ repo https://github.com/a/b.git → ~/b (prime mise install → mise install && gradle deps)"),
                TemplateChanges.definitionChanges(built, more, java.util.Map.of()));
    }

    @Test
    void anUnsetPrimeIsShownAsNone() throws Exception {
        var built = def("repos:\n  - url: https://github.com/a/b.git");
        var now = def("repos:\n  - url: https://github.com/a/b.git\n    prime: mvn b");
        assertEquals(List.of("~ repo https://github.com/a/b.git → ~/b (prime (none) → mvn b)"),
                TemplateChanges.definitionChanges(built, now, java.util.Map.of()));
    }
}
