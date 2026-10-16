package dev.incusspawn.command;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.TempHome;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Why {@code isx run} opens a shell instead of a template's {@code default-action}: none
 * configured, or what is wrong with the reference, as the TUI's Enter says it (#1444).
 */
@ExtendWith(TempHome.class)
class RunCommandDefaultActionTest {

    private static final String NAME = "dev-1";
    private static final String TEMPLATE = "tpl-agent";

    /** A tool with shell actions of the given ids; two make a bare reference to it ambiguous. */
    record Tool(String name, List<String> ids) implements ToolSetup {
        @Override
        public List<ToolDef.ActionEntry> actions() {
            return ids.stream().map(id -> {
                var entry = new ToolDef.ActionEntry();
                entry.setId(id);
                entry.setType("shell");
                entry.setCommand("run-" + id);
                return entry;
            }).toList();
        }

        @Override
        public void install(dev.incusspawn.incus.Container container, Map<String, String> resolvedParams) {}
    }

    /** What {@code isx run} says for a clone of a template whose default-action is {@code ref}, resolved as it resolves it. */
    private static String noDefaultAction(String ref) {
        var def = new ImageDef();
        def.setName(TEMPLATE);
        def.setDefaultAction(ref);
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var resolver = new ActionResolver(daemon.client(), new ToolDefLoader(List.of()),
                List.of(new Tool("agent", List.of("go")), new Tool("ide", List.of("a", "b"))),
                Map.of(TEMPLATE, def));
        var instance = resolver.readInstance(NAME);
        var actions = resolver.resolveActionsForInstance(NAME, TEMPLATE, Set.of("agent", "ide"), List.of());
        return RunCommand.noDefaultAction(NAME, resolver.defaultActionRef(instance, TEMPLATE), actions);
    }

    @Test
    void aToolThatIsNotInstalledIsNamed() {
        assertEquals("default-action 'nosuchtool': tool not found", noDefaultAction("nosuchtool"));
    }

    @Test
    void anUnknownActionIdIsNamed() {
        assertEquals("default-action 'ide:nosuchid': action id not found or ambiguous",
                noDefaultAction("ide:nosuchid"));
    }

    @Test
    void anAmbiguousReferenceAsksForTheActionId() {
        assertEquals("default-action 'ide': ambiguous, use tool:action-id", noDefaultAction("ide"));
    }

    @Test
    void aResolvingReferenceRunsTheAction() {
        assertNull(noDefaultAction("agent"));
        assertNull(noDefaultAction("ide:b"));
    }

    @Test
    void onlyNoReferenceIsNoneConfigured() {
        assertEquals("No default action configured for " + NAME, noDefaultAction(null));
    }

    @Test
    void aDeletedParentYamlRunsTheStampedAction() {
        // tpl-agent is on disk, its parent tpl-base's YAML is gone: the stamp stands in for the
        // inherited default-action, and the repos come from the parent's layer in the build record.
        var parent = new ImageDef();
        parent.setName("tpl-base");
        parent.setDefaultAction("agent");
        parent.setTools(List.of(new ToolDef.ToolRef("agent")));
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/example/project.git");
        repo.setPath("~/project");
        parent.setRepos(List.of(repo));
        var leaf = new ImageDef();
        leaf.setName(TEMPLATE);
        leaf.setParent("tpl-base");
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.PROFILE, TEMPLATE,
                Metadata.DEFAULT_ACTION, "agent",
                Metadata.BUILD_SOURCE, new BuildSource(Map.of(TEMPLATE, leaf, "tpl-base", parent),
                        Map.of(), Map.of(), Map.of()).toJson()));
        var resolver = new ActionResolver(daemon.client(), new ToolDefLoader(List.of()),
                List.of(new Tool("agent", List.of("go"))), Map.of(TEMPLATE, leaf));
        // As RunCommand.doExecute resolves it.
        var instance = resolver.readInstance(NAME);
        var repos = resolver.collectRepos(instance, TEMPLATE);
        assertEquals(List.of("/home/agentuser/project"), repos.stream().map(r -> r.path()).toList());
        var actions = resolver.resolveActionsForInstance(NAME, TEMPLATE,
                resolver.collectInstalledTools(instance, TEMPLATE), repos);
        var ref = resolver.defaultActionRef(instance, TEMPLATE);
        assertNull(RunCommand.noDefaultAction(NAME, ref, actions));
        assertEquals("run-go", ActionResolver.findActionByRef(ref, actions).orElseThrow().shellCommand(null).orElseThrow());
    }
}
