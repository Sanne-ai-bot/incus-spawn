package dev.incusspawn.lifecycle;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The host half of the herdr tool (#1720): an instance whose template installs herdr is added to
 * the host's herdr once, on its first start, and only the machine isx added is removed when it is
 * destroyed. Later starts, and destroys of anything else, run nothing.
 */
class HerdrIntegrationTest {

    private static final String ID = "ae376ca7bf702d2ab8ae75ad49cd411b";
    private static final String USERS = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path records;

    /** A host with or without herdr, answering each command from a script and recording it. */
    static final class FakeHost implements HostCommands {
        final boolean hasHerdr;
        final List<String> ran = new ArrayList<>();
        final Map<String, Result> answers = new HashMap<>();

        FakeHost(boolean hasHerdr) {
            this.hasHerdr = hasHerdr;
            answers.put("herdr machine add", new Result(0, "Saved SSH machine " + ID + ". Remote server is ready."));
            answers.put("herdr machine list --json", new Result(0, "[{\"id\":\"" + ID + "\",\"target\":\"ws\"}]"));
            answers.put("herdr machine remove", new Result(0, "Removed SSH machine " + ID + "."));
        }

        @Override
        public boolean onPath(String command) {
            return hasHerdr && command.equals("herdr");
        }

        @Override
        public Result run(Duration timeout, List<String> command) {
            var line = String.join(" ", command);
            ran.add(line);
            return answers.entrySet().stream().filter(e -> line.startsWith(e.getKey())).map(Map.Entry::getValue)
                    .findFirst().orElse(new Result(127, "not scripted: " + line));
        }
    }

    private HerdrIntegration herdr(FakeHost host) {
        return new HerdrIntegration(host, () -> records);
    }

    private static String buildWith(String tool, boolean projectLocal) {
        var tools = new java.util.LinkedHashMap<String, ToolDef>();
        var def = new ToolDef();
        def.setName(tool);
        tools.put(tool, def);
        var source = new BuildSource(Map.of(), tools, null, null);
        if (projectLocal) source.setProjectRoots(Map.of("tpl-herdr", "/home/u/project"));
        return source.toJson();
    }

    private static FakeIncusDaemon daemon(String name, Map<String, String> config) {
        return new FakeIncusDaemon().instance(name, "container", "Running", config);
    }

    private static RecordingOutput started(HerdrIntegration herdr, FakeIncusDaemon daemon, String name) {
        return RecordingOutput.during(() ->
                herdr.started(daemon.client(), name, daemon.client().instanceMetadataOrThrow(name), BuildOutput.bound()));
    }

    private String recorded(String name) throws Exception {
        return Files.readString(records.resolve(name)).strip();
    }

    @Test
    void theFirstStartAddsTheInstanceAndRecordsItsMachineSoLaterStartsRunNothing() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        var herdr = herdr(host);

        var first = started(herdr, daemon, "ws");
        assertEquals(List.of("herdr machine add ws --label ws"), host.ran);
        assertEquals(ID, recorded("ws"));
        assertEquals(List.of("Added ws to herdr."), first.events(RecordingOutput.Kind.STEP).stream()
                .map(RecordingOutput.Event::msg).toList());
        assertTrue(herdr.done("ws"));
        assertFalse(herdr.done("ws-2"), "a copy, another name, adds itself");

        host.ran.clear();
        RecordingOutput.during(() -> HostIntegration.startedAll(daemon.client(), "ws",
                daemon.client().instanceMetadataOrThrow("ws"), BuildOutput.bound()));
        assertEquals(List.of(), host.ran, "a recorded instance runs nothing");
        assertEquals(List.of("GET /1.0/instances/ws"), daemon.requests().subList(daemon.requests().size() - 1,
                daemon.requests().size()), "and writes nothing to Incus");
    }

    @Test
    void nothingRunsWithoutHerdrOnTheHost() {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(false);
        var recorded = started(herdr(host), daemon, "ws");
        assertEquals(List.of(), host.ran);
        assertEquals(List.of(), recorded.events());
        assertFalse(Files.exists(records.resolve("ws")));
    }

    /** A project-local tool may be called herdr too: a host command is never run for one, and it is said once. */
    @Test
    void aProjectLocalOrUntrustedBuildIsNeverAddedAndSaidOnce() {
        var cases = List.of(Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", true)),
                Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false), Metadata.UNTRUSTED_LINEAGE, "failed-build"));
        for (var i = 0; i < cases.size(); i++) {
            var name = "ws" + i;
            var daemon = daemon(name, cases.get(i));
            var host = new FakeHost(true);
            var herdr = herdr(host);
            var recorded = started(herdr, daemon, name);
            assertEquals(List.of(), host.ran, cases.get(i).toString());
            assertEquals(List.of("Not adding " + name + " to herdr: its template is project-local or untrusted."),
                    recorded.events(RecordingOutput.Kind.STEP).stream().map(RecordingOutput.Event::msg).toList());
            assertTrue(herdr.done(name), "not said again on the next start");
        }
    }

    @Test
    void aFailedAddIsOneWarningWithTheCommandAndIsNotRetriedOnEveryStart() throws Exception {
        var daemon = daemon("ws", Map.of(Metadata.BUILD_SOURCE, buildWith("herdr", false)));
        var host = new FakeHost(true);
        host.answers.put("herdr machine add", new HostCommands.Result(1, "ssh: connect\nerror: could not reach ws"));
        var recorded = started(herdr(host), daemon, "ws");
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.WARN,
                "could not add ws to herdr: error: could not reach ws", "Add it yourself: herdr machine add ws")),
                recorded.events(RecordingOutput.Kind.WARN));
        assertEquals(HerdrIntegration.NOT_ADDED, recorded("ws"));
    }

    @Test
    void aDestroyRemovesOnlyTheMachineIsxAdded() throws Exception {
        var host = new FakeHost(true);
        Files.writeString(records.resolve("ws"), ID + "\n");
        var recorded = RecordingOutput.during(() -> herdr(host).destroyed("ws", BuildOutput.bound()));
        assertEquals(List.of("herdr machine list --json", "herdr machine remove " + ID), host.ran);
        assertEquals(List.of(), recorded.events(RecordingOutput.Kind.WARN));
        assertFalse(Files.exists(records.resolve("ws")));
    }

    /**
     * The user's own herdr machine for a real host called dev survives destroying an isx instance
     * called dev, which isx never added: no record, no command.
     */
    @Test
    void aDestroyOfAnInstanceIsxNeverAddedRunsNothing() {
        var host = new FakeHost(true);
        host.answers.put("herdr machine list --json", new HostCommands.Result(0, "[{\"id\":\"" + USERS + "\",\"target\":\"dev\"}]"));
        herdr(host).destroyed("dev", BuildOutput.bound());
        assertEquals(List.of(), host.ran);
    }

    /** A machine the user already removed by hand, or one isx never added, is only forgotten. */
    @Test
    void aRecordWithoutAMachineIsOnlyForgotten() throws Exception {
        var host = new FakeHost(true);
        host.answers.put("herdr machine list --json", new HostCommands.Result(0, "[]"));
        Files.writeString(records.resolve("ws"), ID + "\n");
        Files.writeString(records.resolve("ws2"), HerdrIntegration.NOT_ADDED + "\n");
        herdr(host).destroyed("ws", BuildOutput.bound());
        herdr(host).destroyed("ws2", BuildOutput.bound());
        assertEquals(List.of("herdr machine list --json"), host.ran);
        assertFalse(Files.exists(records.resolve("ws")));
        assertFalse(Files.exists(records.resolve("ws2")));
    }

    @Test
    void theAddedIdIsTheOneHerdrSaved() {
        assertEquals(ID, HerdrIntegration.addedId("Saved SSH machine " + ID + ". Remote server is ready.\n"));
        assertNull(HerdrIntegration.addedId("Saved SSH machine. Remote server is ready."));
    }

    @Test
    void onlyAnInstanceWhoseBuildInstallsHerdrIsConsidered() {
        assertTrue(BuildSource.installs(buildWith("herdr", false), "herdr"));
        assertFalse(BuildSource.installs(buildWith("zmx", false), "herdr"));
        assertFalse(BuildSource.installs("", "herdr"));
    }
}
