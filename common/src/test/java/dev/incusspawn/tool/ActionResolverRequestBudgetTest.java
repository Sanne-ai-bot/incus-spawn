package dev.incusspawn.tool;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how many Incus round trips building an {@link ActionContext} costs. {@code isx run} pays
 * it before every action, so it is on the same latency path as
 * {@code InstanceLifecycleRequestBudgetTest}'s flows (#979).
 *
 * <p>Budgets are exact: fewer is an improvement, so lower the number here in the same change.
 * Tool resolution reads {@code config.yaml} for feature gates, hence the temporary home.
 */
@ExtendWith(TempHome.class)
class ActionResolverRequestBudgetTest {

    private static final String NAME = "dev-1";
    private static final String TEMPLATE = "tpl-java";

    private static ActionResolver resolver(FakeIncusDaemon daemon) {
        return new ActionResolver(daemon.client(), new ToolDefLoader(), List.of(), Map.of());
    }

    private static String buildSource(String tool) {
        var def = new ImageDef();
        def.setTools(List.of(new ToolDef.ToolRef(tool)));
        return new BuildSource(Map.of(TEMPLATE, def), Map.of(), Map.of(), Map.of()).toJson();
    }

    private static void assertBudget(int expected, FakeIncusDaemon daemon, String flow) {
        var requests = daemon.requests();
        assertEquals(expected, requests.size(), () -> flow + " should make " + expected
                + " Incus request(s), made " + requests.size() + ":\n  "
                + String.join("\n  ", requests)
                + "\nMore is a latency regression; fewer is an improvement -- lower the budget.");
    }

    @Test
    void aRunningInstanceIsReadOnceAndAskedForItsAddress() {
        var daemon = new FakeIncusDaemon()
                .instance(NAME, "container", "Running", Map.of(
                        Metadata.TYPE, Metadata.TYPE_CLONE,
                        Metadata.STATIC_IP, "10.166.11.99",
                        Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(),
                        Metadata.BUILD_SOURCE, buildSource("some-tool")))
                .ipFiltering(NAME, "true");
        var context = resolver(daemon).buildActionContext(NAME, TEMPLATE);
        assertBudget(2, daemon, "buildActionContext (running)");

        assertEquals("GET /1.0/instances/" + NAME + "/state", daemon.requests().get(1));
        assertEquals(MachineType.CONTAINER, context.machineType());
        assertEquals("Running", context.status());
        assertEquals("10.166.11.20", context.ipv4(), "the address the guest holds, not the stamp");
        assertEquals(NetworkMode.PROXY_ONLY.name(), context.networkMode());
        assertEquals(TEMPLATE, context.parent());
        assertEquals(Set.of("some-tool"), context.installedTools(),
                "a clone's tools are the ones it was built with");
    }

    @Test
    void aFrozenInstanceStillReportsTheAddressItHolds() {
        // Paused, not stopped: the guest keeps its network, so /state still has the address.
        var daemon = new FakeIncusDaemon()
                .instance(NAME, "container", "Frozen", Map.of(Metadata.STATIC_IP, "10.166.11.99"))
                .ipFiltering(NAME, "true");
        var context = resolver(daemon).buildActionContext(NAME, TEMPLATE);
        assertBudget(2, daemon, "buildActionContext (frozen)");
        assertEquals("10.166.11.20", context.ipv4(), "the address the guest holds, not the stamp");
    }

    @Test
    void aStoppedVmFallsBackToItsStaticIpWithoutAskingForState() {
        // A stopped guest holds no address, so its /state has nothing to give.
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Stopped", Map.of(
                Metadata.STATIC_IP, "10.166.11.21"));
        var context = resolver(daemon).buildActionContext(NAME, TEMPLATE);
        assertBudget(1, daemon, "buildActionContext (stopped)");

        assertEquals(MachineType.VM, context.machineType());
        assertEquals("Stopped", context.status());
        assertEquals("10.166.11.21", context.ipv4());
        assertEquals("", context.networkMode());
    }

    @Test
    void aBaseTemplatesToolsComeFromItsDefinitionsNotItsBuildSource() {
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_SOURCE, buildSource("some-tool")));
        var def = new ImageDef();
        def.setTools(List.of(new ToolDef.ToolRef("other-tool")));
        var context = new ActionResolver(daemon.client(), new ToolDefLoader(), List.of(), Map.of(TEMPLATE, def))
                .buildActionContext(TEMPLATE, TEMPLATE);
        assertBudget(1, daemon, "buildActionContext (base template)");
        assertEquals(Set.of("other-tool"), context.installedTools(), "the definition on disk is authoritative");
    }

    @Test
    void aBaseTemplateWhoseDefinitionIsGoneFallsBackToItsBuildSource() {
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_SOURCE, buildSource("some-tool")));
        var context = resolver(daemon).buildActionContext(TEMPLATE, TEMPLATE);
        assertBudget(1, daemon, "buildActionContext (base template, YAML deleted)");
        assertEquals(Set.of("some-tool"), context.installedTools(), "what it was built with (#868)");
    }

    @Test
    void collectingInstalledToolsReadsTheInstanceOnce() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.BUILD_SOURCE, buildSource("some-tool")));
        var resolver = resolver(daemon);
        var tools = resolver.collectInstalledTools(resolver.readInstance(NAME), TEMPLATE);
        assertBudget(1, daemon, "collectInstalledTools");
        assertEquals(Set.of("some-tool"), tools);
    }

    @Test
    void runResolvesADefaultActionWhoseYamlIsGoneFromOneRead() {
        // isx run on a template whose definitions were deleted: tools from the build source,
        // the reference from the stamp, and the context to run it in, all from one read (#1159),
        // plus /state for the address of the running instance isx run always has.
        var daemon = runningClone(Map.of(Metadata.DEFAULT_ACTION, "some-tool"));
        var resolver = new ActionResolver(daemon.client(), new ToolDefLoader(),
                List.of(new ActionResolverDefaultCommandTest.Tool("some-tool")), Map.of());
        var instance = resolver.readInstance(NAME);
        var tools = resolver.collectInstalledTools(instance, TEMPLATE);
        var action = resolver.findDefaultAction(instance, TEMPLATE, tools, List.of());
        resolver.buildActionContext(NAME, instance, TEMPLATE);
        IncusClient.ShellPrep.fromInstance(daemon.client(), instance);
        assertInstanceReadOnce(daemon, "isx run's default action (YAML gone)");
        assertEquals("some-tool", action.orElseThrow().toolName(), "the stamped default action");
    }

    @Test
    void runFallsBackToAShellWithItsMenuFromOneRead() throws IOException {
        var config = Path.of(System.getProperty("user.home"), ".config/incus-spawn/config.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "features:\n  - " + ShellMenu.FEATURE + "\n");
        var daemon = runningClone(Map.of());
        var resolver = resolver(daemon);
        var instance = resolver.readInstance(NAME);
        var tools = resolver.collectInstalledTools(instance, TEMPLATE);
        assertTrue(resolver.findDefaultAction(instance, TEMPLATE, tools, List.of()).isEmpty());
        var prep = IncusClient.ShellPrep.fromInstance(daemon.client(), instance);
        resolver.shellMenu(NAME, instance, TEMPLATE, prep.workdir());
        assertInstanceReadOnce(daemon, "isx run's shell with no default action");
    }

    private static FakeIncusDaemon runningClone(Map<String, String> extra) {
        var config = new java.util.HashMap<>(extra);
        config.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        config.put(Metadata.BUILD_SOURCE, buildSource("some-tool"));
        return new FakeIncusDaemon().instance(NAME, "container", "Running", config).ipFiltering(NAME, "true");
    }

    /** The instance read once and its /state once; the shell's subnet check may add its own reads. */
    private static void assertInstanceReadOnce(FakeIncusDaemon daemon, String flow) {
        var instanceRequests = daemon.requests().stream()
                .filter(r -> r.startsWith("GET /1.0/instances/" + NAME))
                .toList();
        assertEquals(List.of("GET /1.0/instances/" + NAME, "GET /1.0/instances/" + NAME + "/state"),
                instanceRequests, () -> flow + " should read the instance once and its /state once, made:\n  "
                        + String.join("\n  ", daemon.requests()));
    }

    @Test
    void aMissingInstanceFailsRatherThanYieldingAnEmptyContext() {
        var resolver = resolver(new FakeIncusDaemon());
        assertThrows(IncusException.class, () -> resolver.buildActionContext(NAME, TEMPLATE));
        assertThrows(IncusException.class, () -> resolver.readInstance(NAME));
    }
}
