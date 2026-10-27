package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The address a template is pinned to is claimed under a lock in the home directory
@ExtendWith(IsolatedHome.class)
class UpdateAllCommandTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String NAME = "tpl-test";

    private final List<String> guestScripts = new ArrayList<>();
    /** Each command run as root, joined with spaces, in order. */
    private final List<String> rootCommands = new ArrayList<>();

    private FakeIncusDaemon daemon;

    /** A stopped base template whose every step succeeds; each test fails one. Records what runs as agentuser. */
    private IncusClient incus() {
        return incus("container");
    }

    private IncusClient incus(String type) {
        daemon = new FakeIncusDaemon().instance(NAME, type, "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE));
        var incus = spy(daemon.client());
        doNothing().when(incus).waitForReady(anyString(), any());
        doAnswer(inv -> {
            var args = inv.getArguments();
            // Matchers pass null while a stub is set up
            if (NAME.equals(args[0])) rootCommands.add(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length, String[].class)));
            return OK;
        }).when(incus).shellExec(anyString(), any(String[].class));
        doReturn(new IncusClient.ExecResult(1, "", "")).when(incus).shellExec(NAME, "which", "npm");
        doAnswer(inv -> {
            guestScripts.add(inv.getArgument(2));
            return OK;
        }).when(incus).execInContainer(eq(NAME), eq("agentuser"), anyString());
        rootCommands.clear();
        return incus;
    }

    /** A base template as read, in {@code status}. */
    private static JqObject template(String status) {
        return JqObject.builder(3).put("status", status).put("type", "container")
                .put("config", JqObject.builder(1).put(Metadata.TYPE, Metadata.TYPE_BASE).build()).build();
    }

    private static int update(IncusClient incus) {
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, null);
        return new UpdateAllCommand().updateTemplates(incus, resolved);
    }

    /**
     * A template starts as an instance does (#1457): on a host that cannot enforce IP spoofing
     * protection the start drops it and goes on, instead of failing the update.
     */
    @Test
    void aTemplateStartsOnAHostThatCannotEnforceIpFiltering() {
        var incus = incus();
        doThrow(new IncusException("Failed to start device \"eth0\": Failed to run: ebtables -t filter -A INPUT"
                + " -i veth0 -j DROP: exit status 255 (The kernel doesn't support the ebtables 'filter' table.)"))
                .doCallRealMethod().when(incus).start(NAME);

        assertEquals(0, update(incus));

        verify(incus).deviceConfigSet(NAME, "eth0", "security.ipv4_filtering", "false");
        verify(incus, times(2)).start(NAME);
    }

    /**
     * A template runs its prime commands as a build does, so it is pinned to an address with IP
     * spoofing protection as a build is (#1743): unfiltered, code in it could take a stopped
     * branch's address and be served that branch's credentials. Once stopped it gives it back.
     */
    @Test
    void aTemplateRunsItsPrimeCommandsOnAPinnedFilteredAddress() throws Exception {
        var incus = incus();
        var nicDuringPrime = new ArrayList<JqValue>();
        var staticIpDuringPrime = new ArrayList<String>();
        doAnswer(inv -> {
            var instance = daemon.instance(NAME);
            nicDuringPrime.add(instance.path("expanded_devices").path("eth0"));
            staticIpDuringPrime.add(instance.path("config").pathText(Metadata.STATIC_IP, ""));
            return OK;
        }).when(incus).execInContainer(eq(NAME), eq("agentuser"), contains("./prime.sh"));
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, ImageDef.parseYaml("name: " + NAME + "\nrepos:\n  - url: https://github.com/a/b.git\n"
                + "    path: ~/b\n    prime: ./prime.sh\n"));
        var command = new UpdateAllCommand();
        command.prime = true;

        assertEquals(0, command.updateTemplates(incus, resolved));

        assertEquals(1, nicDuringPrime.size(), "the prime command ran");
        var nic = nicDuringPrime.getFirst();
        assertEquals("true", nic.pathText("security.ipv4_filtering", ""),
                "without filtering the template can take any address on the bridge");
        assertFalse(staticIpDuringPrime.getFirst().isEmpty(), "the template is known to the proxy by its own address");
        assertEquals(staticIpDuringPrime.getFirst(), nic.pathText("ipv4.address", ""));
        var after = daemon.instance(NAME);
        assertEquals("Stopped", after.pathText("status", ""));
        assertFalse(after.path("config").has(Metadata.STATIC_IP), "the stopped template gives its address back");
        assertEquals(0, after.path("devices").size(), "the NIC is the profile's again");
    }

    /** A template that has been granted nothing is served nothing: no proof or grant goes with its address. */
    @Test
    void aTemplateIsPinnedWithoutProofsOrGrants() {
        var incus = incus();
        daemon.instance(NAME, "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.INSTANCE_PROOF_SHA256, "stale", Metadata.INSTANCE_SECRET_SHA256, "stale",
                Metadata.MCP_CALLER, "true"));
        var configDuringUpdate = new ArrayList<JqValue>();
        doAnswer(inv -> {
            configDuringUpdate.add(daemon.instance(NAME).path("config"));
            return OK;
        }).when(incus).shellExec(NAME, "dnf", "update", "-y");

        assertEquals(0, update(incus));

        var config = configDuringUpdate.getFirst();
        assertFalse(config.pathText(Metadata.STATIC_IP, "").isEmpty());
        assertFalse(config.has(Metadata.INSTANCE_PROOF_SHA256));
        assertFalse(config.has(Metadata.INSTANCE_SECRET_SHA256));
        assertFalse(config.has(Metadata.MCP_CALLER));
    }

    /** A template that would not start gives back the address it was pinned to. */
    @Test
    void aTemplateThatFailsToStartGivesItsAddressBack() {
        var incus = incus();
        doThrow(new IncusException("Failed to start: disk full")).when(incus).start(NAME);

        assertThrows(IncusException.class, () -> update(incus));

        assertFalse(daemon.instance(NAME).path("config").has(Metadata.STATIC_IP));
        assertEquals(0, daemon.instance(NAME).path("devices").size());
    }

    /** Pinning changes the NIC, which must not happen under a running guest: such a template is refused, and left as it is. */
    @Test
    void aRunningTemplateIsRefusedWithoutTouchingIt() {
        var incus = incus();
        daemon.instance(NAME, "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_BASE));

        var e = assertThrows(IllegalStateException.class, () -> update(incus));

        assertTrue(e.getMessage().contains("must be stopped"), e.getMessage());
        assertEquals("Running", daemon.instance(NAME).pathText("status", ""));
        assertEquals(0, daemon.instance(NAME).path("devices").size());
        verify(incus, never()).stop(NAME);
    }

    /** A template the update leaves running fails the next update-all's start. */
    @Test
    void aTemplateIsStoppedWhenItsUpdateThrows() {
        var incus = incus();
        doThrow(new IncusException("Timed out waiting for tpl-test")).when(incus).waitForReady(eq(NAME), any());

        assertThrows(IncusException.class, () -> update(incus));

        verify(incus).stop(NAME);
    }

    @Test
    void aTemplateIsStoppedWhenAGuestStepThrows() {
        var incus = incus();
        doThrow(new IncusException("exec failed")).when(incus).shellExec(NAME, "dnf", "update", "-y");

        assertThrows(IncusException.class, () -> update(incus));

        verify(incus).stop(NAME);
    }

    /** The guest that never answered may ignore the shutdown too: it is forced, and why the update failed stays the error. */
    @Test
    void aTemplateThatIgnoresTheStopIsForcedWithoutHidingWhyTheUpdateFailed() {
        var incus = incus();
        doThrow(new IncusException("Timed out waiting for tpl-test")).when(incus).waitForReady(eq(NAME), any());
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(NAME);

        var e = assertThrows(IncusException.class, () -> update(incus));

        assertEquals("Timed out waiting for tpl-test", e.getMessage());
        verify(incus).forceStop(NAME);
    }

    /** Incus refuses to stop a stopped instance: one that died is stopped, not one that cannot be. */
    @Test
    void aTemplateThatDiedIsNotForceStopped() {
        var incus = incus();
        doReturn(template("Stopped")).when(incus).instanceMetadataOrThrow(NAME);
        doThrow(new IncusException("Timed out waiting for tpl-test")).when(incus).waitForReady(eq(NAME), any());
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(NAME);

        var e = assertThrows(IncusException.class, () -> update(incus));

        assertEquals("Timed out waiting for tpl-test", e.getMessage());
        verify(incus, never()).forceStop(any());
    }

    @Test
    void aTemplateAlreadyStoppedAtTheEndDoesNotFailTheCommand() {
        var incus = incus();
        doReturn(template("Stopped")).when(incus).instanceMetadataOrThrow(NAME);
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(NAME);

        assertEquals(0, update(incus));
        verify(incus, never()).forceStop(any());
    }

    /** Not knowing the status is no reason to leave the template running. */
    @Test
    void aTemplateIsForceStoppedWhenItsStatusCannotBeRead() {
        var incus = incus();
        doThrow(new IncusException("Failed to read instance 'tpl-test' (HTTP 500)")).when(incus).instanceMetadataOrThrow(NAME);
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(NAME);

        assertEquals(0, update(incus));
        verify(incus).forceStop(NAME);
    }

    @Test
    void aTemplateGoneByTheEndIsNotForceStopped() {
        var incus = incus();
        doReturn(null).when(incus).instanceMetadataOrThrow(NAME);
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(NAME);

        assertEquals(0, update(incus));
        verify(incus, never()).forceStop(any());
    }

    @Test
    void aTemplateThatCannotBeStoppedFailsTheCommand() {
        var incus = incus();
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(NAME);
        doThrow(new IncusException("Failed to force-stop tpl-test")).when(incus).forceStop(NAME);

        assertEquals(1, update(incus));
    }

    @Test
    void anUpdateEmptiesTmpBeforeTheTemplateIsStopped() {
        var incus = incus();
        assertEquals(0, update(incus));
        var order = inOrder(incus);
        order.verify(incus).execInContainer(NAME, "agentuser", GuestUpdate.GIT_FETCH_SCRIPT);
        order.verify(incus).shellExec(NAME, "sh", "-c", GuestUpdate.EMPTY_TMP_SCRIPT);
        order.verify(incus).stop(NAME);
    }

    @Test
    void aTmpThatCannotBeEmptiedDoesNotFailTheUpdate() {
        var incus = incus();
        doThrow(new IncusException("exec failed")).when(incus).shellExec(NAME, "sh", "-c", GuestUpdate.EMPTY_TMP_SCRIPT);
        assertEquals(0, update(incus));
        verify(incus).stop(NAME);
    }

    @Test
    void succeedsWhenEveryStepSucceeds() {
        assertEquals(0, update(incus()));
    }

    @Test
    void aFailedSystemUpdateFailsTheCommand() {
        var incus = incus();
        doReturn(new IncusClient.ExecResult(1, "", "Failed to download metadata"))
                .when(incus).shellExec(NAME, "dnf", "update", "-y");

        assertEquals(1, update(incus));
    }

    /**
     * A template boots on DHCP, and exec answers before the lease is in place: dnf run then
     * cannot resolve its mirrors (#1734). The network is waited for first, in one exec.
     */
    @Test
    void theSystemUpdateWaitsForTheNetwork() {
        assertEquals(0, update(incus()));

        assertTrue(rootCommands.size() > 1, rootCommands.toString());
        assertTrue(rootCommands.getFirst().contains("mirrors.fedoraproject.org"),
                "the first exec must wait for the network: " + rootCommands);
        assertEquals("dnf update -y", rootCommands.get(1));
    }

    /** A VM's NIC is not eth0 (#997), and its guest may not run systemd-networkd. */
    @Test
    void aVmTemplateWaitsForItsNetworkWithoutNamingANic() {
        var incus = incus("virtual-machine");

        assertEquals(0, update(incus));

        var wait = rootCommands.getFirst();
        assertTrue(wait.contains("mirrors.fedoraproject.org"), rootCommands.toString());
        assertTrue(wait.contains("ip -4 route show default"), wait);
        assertFalse(wait.contains("eth0"), wait);
        assertFalse(wait.contains("systemd-networkd"), wait);
    }

    /** No step that needs the network runs without one; the update fails, saying what it waited for. */
    @Test
    void aNetworkThatNeverComesUpFailsTheUpdateWithoutRunningDnf() {
        var incus = incus();
        doReturn(new IncusClient.ExecResult(4, "", ""))
                .when(incus).shellExec(eq(NAME), eq("sh"), eq("-c"), contains("mirrors.fedoraproject.org"));

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        verify(incus, never()).shellExec(NAME, "dnf", "update", "-y");
        verify(incus, never()).execInContainer(eq(NAME), eq("agentuser"), anyString());
        verify(incus).stop(NAME);
        assertTrue(failedSteps(said).contains("cannot reach https://mirrors.fedoraproject.org"), failedSteps(said));
    }

    /** No address at all: the guest's own network state is what explains it. */
    @Test
    void aTemplateWithoutAnAddressFailsWithTheGuestsNetworkState() {
        var incus = incus();
        doReturn(new IncusClient.ExecResult(3, "", ""))
                .when(incus).shellExec(eq(NAME), eq("sh"), eq("-c"), contains("mirrors.fedoraproject.org"));
        doReturn(new IncusClient.ExecResult(0, "eth0: no carrier", ""))
                .when(incus).shellExec(eq(NAME), eq("sh"), eq("-c"), contains("networkctl"));

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        verify(incus, never()).shellExec(NAME, "dnf", "update", "-y");
        var failed = failedSteps(said);
        assertTrue(failed.contains(NAME + " did not acquire an IPv4 address"), failed);
        assertTrue(failed.contains("eth0: no carrier"), failed);
    }

    /** {@code --prime} runs a repo's prime commands in turn and names the first that fails (#1745). */
    @Test
    void primeRunsEachCommandInTurnAndStopsAtTheFirstFailure() throws Exception {
        var incus = incus();
        when(incus.execInContainer(NAME, "agentuser", "cd '/home/agentuser/app' && ./gradlew dependencies"))
                .thenAnswer(inv -> {
                    guestScripts.add(inv.getArgument(2));
                    return new IncusClient.ExecResult(1, "", "");
                });
        guestScripts.clear(); // stubbing ran the default answer
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/app.git");
        repo.setPath("~/app");
        repo.setPrime(List.of("mise install", "./gradlew dependencies", "echo never"));
        var def = new ImageDef();
        def.setRepos(List.of(repo));
        var command = new UpdateAllCommand();
        var prime = UpdateAllCommand.class.getDeclaredField("prime");
        prime.setAccessible(true);
        prime.setBoolean(command, true);
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, def);

        var said = RecordingOutput.during(() -> assertEquals(1, command.updateTemplates(incus, resolved)));

        assertEquals(List.of("cd '/home/agentuser/app' && mise install", "cd '/home/agentuser/app' && ./gradlew dependencies"),
                guestScripts.subList(1, guestScripts.size()), "after the git fetch script");
        assertTrue(failedSteps(said).contains("Prime command failed for ~/app: ./gradlew dependencies"), failedSteps(said));
    }

    private static String failedSteps(RecordingOutput said) {
        return String.join("\n", said.events(RecordingOutput.Kind.STEP_FAIL).stream()
                .map(RecordingOutput.Event::msg).toList());
    }

    @Test
    void aFailedGitFetchFailsTheCommand() {
        var incus = incus();
        doReturn(new IncusClient.ExecResult(1, "", "git fetch failed in /home/agentuser/repo/"))
                .when(incus).execInContainer(eq(NAME), eq("agentuser"), anyString());

        assertEquals(1, update(incus));
    }

    @Test
    void theGitFetchScriptFetchesEveryRepoAndReportsAFailure(@TempDir Path home) throws Exception {
        update(incus());
        assertEquals(1, guestScripts.size());
        var script = guestScripts.getFirst();

        // a repository with no remotes fetches nothing, successfully
        git(home, "init", "-q", "good");
        Files.createDirectories(home.resolve("not-a-repo"));
        assertEquals(0, runAsLoginShell(script, home), "the script must parse and succeed");

        git(home, "init", "-q", "broken");
        git(home.resolve("broken"), "remote", "add", "origin", home.resolve("missing").toString());
        assertEquals(1, runAsLoginShell(script, home), "a failed fetch must fail the script");
    }

    /** Runs {@code script} the way {@code su - agentuser -c} does: one bash command line. */
    private static int runAsLoginShell(String script, Path home) throws Exception {
        var pb = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        var process = pb.start();
        process.getInputStream().transferTo(System.out);
        int exit = process.waitFor();
        return exit;
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        assertEquals(0, new ProcessBuilder(command).inheritIO().start().waitFor());
    }
}
