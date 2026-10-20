package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.baseimage.BaseImageReleases.Release;
import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.AccountUsage;
import dev.incusspawn.proxy.ProxyHealthCheck.ProxyInfo;
import dev.incusspawn.proxy.ProxyHealthCheck.ProxyStatus;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.vm.VmManager;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code --format=plain|json} of every query command but {@code isx list}, which
 * {@link ListCommandOutputTest} covers (#1038). {@code plain} and {@code json} are a contract with
 * scripts, so these are golden outputs: a field may be added at the end, never renamed, removed or
 * reordered. Each command builds one ordered map per record and both formats print it, so pinning
 * the JSON pins the order of the plain fields too.
 */
@ExtendWith(IsolatedHome.class)
class QueryCommandFormatTest {

    private static String json(Object value) {
        var bytes = new ByteArrayOutputStream();
        OutputFormat.printJson(new PrintStream(bytes, true, StandardCharsets.UTF_8), value);
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String plain(List<? extends Map<String, ?>> records) {
        var bytes = new ByteArrayOutputStream();
        OutputFormat.printPlain(new PrintStream(bytes, true, StandardCharsets.UTF_8), records);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private record Run(CommandResult result, String out, String err) {}

    /** Runs a command's {@code doExecute} with stdout and stderr captured. */
    private static Run run(Callable<CommandResult> command) throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var oldOut = System.out;
        var oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            var result = command.call();
            return new Run(result, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private static List<List<String>> keysOf(String jsonArray) throws Exception {
        var nodes = new ObjectMapper().readTree(jsonArray);
        assertTrue(nodes.isArray(), jsonArray);
        var keys = new java.util.ArrayList<List<String>>();
        nodes.forEach(n -> {
            var names = new java.util.ArrayList<String>();
            n.fieldNames().forEachRemaining(names::add);
            keys.add(names);
        });
        return keys;
    }

    // ── isx templates ───────────────────────────────────────────────────────────

    @Test
    void templatesJsonIsOneObjectPerDefinition() throws Exception {
        var cmd = new TemplatesCommand.ListSub();
        cmd.format = "json";
        var run = run(cmd::doExecute);
        assertEquals(CommandResult.SUCCESS, run.result());
        var keys = keysOf(run.out());
        assertFalse(keys.isEmpty());
        // The values of the build fields depend on this host's Incus; TemplatesStalenessTest pins them.
        keys.forEach(k -> assertEquals(List.of("name", "parent", "source", "description", "built", "built_at",
                "version_outdated", "definition_changed", "parent_rebuilt"), k));
        assertTrue(run.out().contains("""
                {
                  "name" : "tpl-dev",
                  "parent" : "tpl-minimal",
                  "source" : "built-in",
                  "description" : "Podman, GitHub CLI, Starship",
                  "built" : """), run.out());
    }

    @Test
    void templatesPlainHasNoHeaderAndADashForNoParent() throws Exception {
        var cmd = new TemplatesCommand();
        cmd.format = "plain";
        var run = run(cmd::doExecute);
        assertTrue(run.out().lines().anyMatch(l -> l.startsWith("tpl-minimal\t-\tbuilt-in\tBase OS only\t")), run.out());
        assertFalse(run.out().contains("NAME"), run.out());
    }

    // ── isx tools ───────────────────────────────────────────────────────────────

    @Test
    void toolsListJsonHasNameSourceDescription() throws Exception {
        var cmd = new ToolsCommand.ListSub();
        cmd.format = "json";
        var run = run(cmd::doExecute);
        assertEquals(CommandResult.SUCCESS, run.result());
        var keys = keysOf(run.out());
        assertTrue(keys.size() >= 10, run.out());
        keys.forEach(k -> assertEquals(List.of("name", "source", "description"), k));
    }

    @Test
    void toolsShowIsOneObject() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setDomains(List.of("api.example.com", "uploads.example.com"));
        var proxy = new ToolDef.ProxyDef();
        proxy.setAuth(List.of(auth));
        ToolSetup tool = new ToolSetup() {
            @Override public String name() { return "example"; }
            @Override public String description() { return "An example"; }
            @Override public List<String> requires() { return List.of("nodejs"); }
            @Override public List<String> packages() { return List.of("jq", "git"); }
            @Override public ToolDef.ProxyDef proxy() { return proxy; }
            @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) { }
        };
        var record = ToolsCommand.Show.record(tool, "built-in");
        assertEquals("""
                {
                  "name" : "example",
                  "description" : "An example",
                  "source" : "built-in",
                  "feature" : null,
                  "requires" : [ "nodejs" ],
                  "packages" : [ "jq", "git" ],
                  "parameters" : [ ],
                  "actions" : [ ],
                  "downloads" : [ ],
                  "proxy_domains" : [ "api.example.com", "uploads.example.com" ]
                }
                """, json(record));
        assertEquals("example\tAn example\tbuilt-in\t-\tnodejs\tjq,git\t-\t-\t-\tapi.example.com,uploads.example.com\n",
                plain(List.of(record)));
    }

    // ── isx account ─────────────────────────────────────────────────────────────

    private static final List<AccountCommand.NamespaceListing> ACCOUNTS = List.of(
            new AccountCommand.NamespaceListing("claude", List.of(
                    new AccountCommand.AccountLine("work", "Vertex AI", true, "", List.of("dev-1"), List.of("dev-2")),
                    new AccountCommand.AccountLine("home", "", false, "incomplete", List.of(), List.of())),
                    Map.of("gone", List.of("dev-3"))));

    @Test
    void accountListIsOneRecordPerAccount() {
        assertEquals("""
                [ {
                  "namespace" : "claude",
                  "account" : "work",
                  "description" : "Vertex AI",
                  "default" : true,
                  "problem" : null,
                  "pinned_by" : [ "dev-1" ],
                  "following" : [ "dev-2" ]
                }, {
                  "namespace" : "claude",
                  "account" : "home",
                  "description" : null,
                  "default" : false,
                  "problem" : "incomplete",
                  "pinned_by" : [ ],
                  "following" : [ ]
                }, {
                  "namespace" : "claude",
                  "account" : "gone",
                  "description" : null,
                  "default" : false,
                  "problem" : "not-configured",
                  "pinned_by" : [ "dev-3" ],
                  "following" : [ ]
                } ]
                """, json(AccountCommand.listRecords(ACCOUNTS, true)));
        assertEquals("""
                claude\twork\tVertex AI\ttrue\t-\tdev-1\tdev-2
                claude\thome\t-\tfalse\tincomplete\t-\t-
                claude\tgone\t-\tfalse\tnot-configured\tdev-3\t-
                """, plain(AccountCommand.listRecords(ACCOUNTS, true)));
    }

    @Test
    void accountListSaysUnknownPinsAreUnknownNotNone() {
        var first = AccountCommand.listRecords(ACCOUNTS, false).getFirst();
        assertTrue(first.containsKey("pinned_by"));
        assertNull(first.get("pinned_by"));
        assertNull(first.get("following"));
    }

    @Test
    void accountShowIsOneRecordPerNamespace() {
        var uses = List.of(
                new AccountUsage.Use("claude", "work", AccountOrigin.template("tpl-acme"), "work",
                        "Vertex AI", "", ""),
                new AccountUsage.Use("github", "me", null, "", "", "", ""),
                new AccountUsage.Use("gitlab", "old", AccountOrigin.EXPLICIT, "bot", "", "Account 'old' is not configured",
                        "Account 'bot' is not configured under 'gitlab'."));
        assertEquals("""
                [ {
                  "namespace" : "claude",
                  "account" : "work",
                  "description" : "Vertex AI",
                  "chosen_by" : "template",
                  "chosen_in" : "tpl-acme",
                  "template" : "tpl-acme",
                  "template_account" : "work",
                  "problem" : null,
                  "identity_pending" : null,
                  "template_problem" : null
                }, {
                  "namespace" : "github",
                  "account" : "me",
                  "description" : null,
                  "chosen_by" : "default",
                  "chosen_in" : null,
                  "template" : "tpl-acme",
                  "template_account" : null,
                  "problem" : null,
                  "identity_pending" : "me <me@example.com>",
                  "template_problem" : null
                }, {
                  "namespace" : "gitlab",
                  "account" : "old",
                  "description" : null,
                  "chosen_by" : "explicit",
                  "chosen_in" : null,
                  "template" : "tpl-acme",
                  "template_account" : "bot",
                  "problem" : "Account 'old' is not configured",
                  "identity_pending" : null,
                  "template_problem" : "Account 'bot' is not configured under 'gitlab'."
                } ]
                """, json(AccountCommand.showRecords("tpl-acme", uses, Map.of("github", "me <me@example.com>"))));
    }

    // ── isx proxy status ────────────────────────────────────────────────────────

    @Test
    void proxyStatusOfARunningProxy() {
        var build = BuildInfo.instance();
        var info = new ProxyInfo(build.version(), build.gitSha(), "native", "ab:cd", true, true, null);
        assertEquals("""
                {
                  "status" : "running",
                  "version" : "%s",
                  "git_sha" : "%s",
                  "runtime" : "native",
                  "dns_overrides" : true,
                  "drift" : "(config has changed since the proxy started)",
                  "auth_error" : null,
                  "health_endpoint" : "http://10.166.11.1:18080/health",
                  "mitm_port" : 18443,
                  "service_installed" : true,
                  "managed_by" : "systemd",
                  "restart_helps" : true,
                  "check_error" : null
                }
                """.formatted(build.version(), build.gitSha()),
                json(ProxyCommand.Status.record(ProxyStatus.RUNNING, info, "10.166.11.1", true, "systemd", null)));
    }

    @Test
    void proxyStatusOfAStoppedProxyKnowsOnlyWhereItWouldBe() {
        assertEquals("""
                {
                  "status" : "stale_dns",
                  "version" : null,
                  "git_sha" : null,
                  "runtime" : null,
                  "dns_overrides" : null,
                  "drift" : null,
                  "auth_error" : null,
                  "health_endpoint" : "http://10.166.11.1:18080/health",
                  "mitm_port" : 18443,
                  "service_installed" : false,
                  "managed_by" : null,
                  "restart_helps" : null,
                  "check_error" : null
                }
                """, json(ProxyCommand.Status.record(ProxyStatus.STALE_DNS, null, "10.166.11.1", false, null, null)));
    }

    @Test
    void aProxyThatCouldNotBeCheckedIsUnknownNotStopped() {
        assertEquals("""
                {
                  "status" : "unknown",
                  "version" : null,
                  "git_sha" : null,
                  "runtime" : null,
                  "dns_overrides" : null,
                  "drift" : null,
                  "auth_error" : null,
                  "health_endpoint" : null,
                  "mitm_port" : 18443,
                  "service_installed" : true,
                  "managed_by" : null,
                  "restart_helps" : null,
                  "check_error" : "Could not determine Incus bridge gateway IP: no socket"
                }
                """, json(ProxyCommand.Status.record(null, null, null, true, null,
                "Could not determine Incus bridge gateway IP: no socket")));
    }

    @Test
    void aForegroundProxyOnAnOldAddressIsStillManual() {
        assertEquals("manual", ProxyCommand.Status.managedBy(ProxyStatus.STALE_GATEWAY, false, false));
        assertEquals("manual", ProxyCommand.Status.managedBy(ProxyStatus.RUNNING, false, true));
        assertNull(ProxyCommand.Status.managedBy(ProxyStatus.NOT_RUNNING, false, false));
        assertEquals("launchd", ProxyCommand.Status.managedBy(ProxyStatus.STALE_GATEWAY, true, true));
        assertEquals("systemd", ProxyCommand.Status.managedBy(ProxyStatus.STALE_DNS, true, false));
    }

    @Test
    void proxyStatusExitCodesAreTheSameInEveryFormat() {
        assertEquals(0, ProxyCommand.Status.exitCode(ProxyStatus.RUNNING));
        assertEquals(0, ProxyCommand.Status.exitCode(ProxyStatus.WAITING_FOR_DNS));
        assertEquals(1, ProxyCommand.Status.exitCode(ProxyStatus.NOT_RUNNING));
        assertEquals(2, ProxyCommand.Status.exitCode(ProxyStatus.STALE_DNS));
        assertEquals(3, ProxyCommand.Status.exitCode(ProxyStatus.STALE_GATEWAY));
    }

    // ── isx doctor ──────────────────────────────────────────────────────────────

    @Test
    void doctorIsOneRecordPerFindingWithItsLevel() {
        var findings = List.of(
                DoctorCommand.Finding.ok("Incus", "(reachable)"),
                DoctorCommand.Finding.note("Pool", ""),
                DoctorCommand.Finding.warn("Proxy", "not running",
                        new DoctorCommand.Remediation("Start the proxy", false, null)),
                DoctorCommand.Finding.fail("DNS", "(no answer) — retry", null));
        assertEquals("""
                [ {
                  "level" : "ok",
                  "label" : "Incus",
                  "detail" : "reachable",
                  "remediation" : null
                }, {
                  "level" : "note",
                  "label" : "Pool",
                  "detail" : null,
                  "remediation" : null
                }, {
                  "level" : "warn",
                  "label" : "Proxy",
                  "detail" : "not running",
                  "remediation" : "Start the proxy"
                }, {
                  "level" : "fail",
                  "label" : "DNS",
                  "detail" : "(no answer) — retry",
                  "remediation" : null
                } ]
                """, json(DoctorCommand.findingRecords(findings)));
    }

    // ── isx vm status ───────────────────────────────────────────────────────────

    @Test
    void vmStatusOfARunningVm() {
        var state = new VmManager.State(true, 4242, "http://127.0.0.1:7777", "/home/u/vm.log",
                "2026.10.1", "2026.10.2", 3);
        assertEquals("""
                {
                  "running" : true,
                  "pid" : 4242,
                  "rest_api" : "http://127.0.0.1:7777",
                  "log" : "/home/u/vm.log",
                  "appliance" : "2026.10.1",
                  "appliance_pending" : "2026.10.2",
                  "vsock_connections" : 3,
                  "incus_reachable" : true,
                  "incus_error" : null,
                  "vsock_connections_high" : false
                }
                """, json(VmCommand.Status.record(state, null)));
        assertEquals("""
                VM running (pid=4242)
                  REST API: http://127.0.0.1:7777
                  Log: /home/u/vm.log
                  Appliance: 2026.10.1  (installed: 2026.10.2 — restart to apply)
                  vsock forwarder connections: 3""", state.render());
    }

    @Test
    void vmStatusSaysWhenTheForwarderLooksLeaky() {
        var leaky = new VmManager.State(true, 1, null, "/l", null, null, VmManager.VSOCK_CONN_WARN_THRESHOLD + 1);
        assertEquals(true, VmCommand.Status.record(leaky, null).get("vsock_connections_high"));
    }

    @Test
    void vmStatusOfAStoppedVm() {
        var state = new VmManager.State(false, -1, null, null, null, null, -1);
        assertEquals("false\t-\t-\t-\t-\t-\t-\tfalse\tno socket\t-\n",
                plain(List.of(VmCommand.Status.record(state, "no socket"))));
    }

    @Test
    void vmStatusExitCodeIsTheSameInEveryFormat() {
        // Both the table and the machine formats return this: 1 when Incus did not answer.
        assertEquals(0, VmCommand.Status.exitCode(null));
        assertEquals(1, VmCommand.Status.exitCode("no socket"));
    }

    // ── isx update-base --list ──────────────────────────────────────────────────

    @Test
    void updateBaseListIsNewestFirstWithLatestAndCurrent() {
        var releases = List.of(new Release("fedora-44-20261001", "2026-10-01", "u1", null),
                new Release("fedora-44-20260901", "", "u2", null));
        assertEquals("""
                [ {
                  "tag" : "fedora-44-20261001",
                  "date" : "2026-10-01",
                  "latest" : true,
                  "current" : false,
                  "pinned" : false,
                  "listed" : true
                }, {
                  "tag" : "fedora-44-20260901",
                  "date" : null,
                  "latest" : false,
                  "current" : true,
                  "pinned" : true,
                  "listed" : true
                } ]
                """, json(UpdateBaseCommand.releaseRecords(releases, "fedora-44-20260901", true)));
    }

    @Test
    void aCurrentBaseOlderThanEveryFetchedReleaseStillHasARecord() {
        var releases = List.of(new Release("fedora-44-20261001", "2026-10-01", "u1", null));
        assertEquals("""
                fedora-44-20261001\t2026-10-01\ttrue\tfalse\tfalse\ttrue
                fedora-44-20250101\t-\tfalse\ttrue\ttrue\tfalse
                """, plain(UpdateBaseCommand.releaseRecords(releases, "fedora-44-20250101", true)));
        // No current tag recorded: nothing to add.
        assertEquals(1, UpdateBaseCommand.releaseRecords(releases, null, false).size());
    }

    // ── isx branch ──────────────────────────────────────────────────────────────

    @Test
    void branchPrintsTheNewNameAlone() {
        assertEquals("dev-2\n", plain(List.of(BranchCommand.record("dev-2"))));
        assertEquals("""
                {
                  "name" : "dev-2"
                }
                """, json(BranchCommand.record("dev-2")));
    }

    private static dev.incusspawn.proxy.ProxyActivity activity() {
        var at = java.time.Instant.parse("2026-10-11T10:00:00Z");
        var traffic = new dev.incusspawn.proxy.ProxyActivity.Traffic(
                new dev.incusspawn.proxy.ProxyActivity.Counts(5, 1, at.plusSeconds(60), 300, 4000),
                Map.of("api.anthropic.com", new dev.incusspawn.proxy.ProxyActivity.Counts(3, 1, at.plusSeconds(60), 200, 3000),
                        "github.com", new dev.incusspawn.proxy.ProxyActivity.Counts(2, 0, at.plusSeconds(30), 100, 1000)));
        return new dev.incusspawn.proxy.ProxyActivity(
                Map.of("worker", new dev.incusspawn.proxy.ProxyActivity.Instance(at, 2, 1, at.plusSeconds(60), at.plusSeconds(45),
                        10, 20, 30, 40, traffic)),
                at.minusSeconds(3600),
                new dev.incusspawn.proxy.ProxyActivity.Traffic(
                        new dev.incusspawn.proxy.ProxyActivity.Counts(1, 0, at, 0, 50),
                        Map.of("registry.npmjs.org", new dev.incusspawn.proxy.ProxyActivity.Counts(1, 0, at, 0, 50))));
    }

    @Test
    void proxyActivityHasOneRecordPerInstanceThenTheUnattributedOne() {
        var records = ProxyCommand.Activity.records(activity(), java.time.ZoneOffset.UTC);
        assertEquals("""
                [ {
                  "instance" : "worker",
                  "counting_since" : "2026-10-11T10:00:00Z",
                  "requests" : 5,
                  "in_flight" : 1,
                  "last_activity_at" : "2026-10-11T10:01:00Z",
                  "request_bytes" : 300,
                  "response_bytes" : 4000,
                  "model_requests" : 2,
                  "model_in_flight" : 1,
                  "last_model_request_at" : "2026-10-11T10:01:00Z",
                  "last_model_response_at" : "2026-10-11T10:00:45Z",
                  "input_tokens" : 10,
                  "output_tokens" : 20,
                  "cache_read_input_tokens" : 30,
                  "cache_creation_input_tokens" : 40,
                  "identified_by_proof" : false,
                  "refused_proofs" : null,
                  "refused_address_misses" : null,
                  "registry_stale" : null,
                  "unplaced_running" : null,
                  "last_address_miss_at" : null
                }, {
                  "instance" : null,
                  "counting_since" : "2026-10-11T09:00:00Z",
                  "requests" : 1,
                  "in_flight" : 0,
                  "last_activity_at" : "2026-10-11T10:00:00Z",
                  "request_bytes" : 0,
                  "response_bytes" : 50,
                  "model_requests" : null,
                  "model_in_flight" : null,
                  "last_model_request_at" : null,
                  "last_model_response_at" : null,
                  "input_tokens" : null,
                  "output_tokens" : null,
                  "cache_read_input_tokens" : null,
                  "cache_creation_input_tokens" : null,
                  "identified_by_proof" : null,
                  "refused_proofs" : 0,
                  "refused_address_misses" : 0,
                  "registry_stale" : false,
                  "unplaced_running" : [ ],
                  "last_address_miss_at" : null
                } ]
                """, json(records));
        assertEquals("worker\t2026-10-11T10:00:00Z\t5\t1\t2026-10-11T10:01:00Z\t300\t4000\t2\t1"
                        + "\t2026-10-11T10:01:00Z\t2026-10-11T10:00:45Z\t10\t20\t30\t40\tfalse\t-\t-\t-\t-\t-\n"
                        + "-\t2026-10-11T09:00:00Z\t1\t0\t2026-10-11T10:00:00Z\t0\t50\t-\t-\t-\t-\t-\t-\t-\t-\t-\t0\t0\tfalse\t-\t-\n",
                plain(records));
    }

    @Test
    void proxyActivityByDomainHasOneRecordPerInstanceAndDomain() {
        assertEquals("""
                worker\tapi.anthropic.com\t2026-10-11T10:00:00Z\t3\t1\t2026-10-11T10:01:00Z\t200\t3000
                worker\tgithub.com\t2026-10-11T10:00:00Z\t2\t0\t2026-10-11T10:00:30Z\t100\t1000
                -\tregistry.npmjs.org\t2026-10-11T09:00:00Z\t1\t0\t2026-10-11T10:00:00Z\t0\t50
                """, plain(ProxyCommand.Activity.domainRecords(activity(), java.time.ZoneOffset.UTC)));
        assertEquals(List.of("instance", "domain", "counting_since", "requests", "in_flight", "last_activity_at",
                        "request_bytes", "response_bytes"),
                List.copyOf(ProxyCommand.Activity.domainRecords(activity(), java.time.ZoneOffset.UTC).get(0).keySet()));
    }

    @Test
    void proxyActivityTableNamesTheUnattributedRow() {
        var bytes = new ByteArrayOutputStream();
        ProxyCommand.Activity.print(activity(), OutputFormat.TABLE, false, java.time.ZoneOffset.UTC,
                java.time.Instant.parse("2026-10-11T10:02:00Z"), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        var table = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(table.contains("(unattributed)"), table);
        assertTrue(table.lines().anyMatch(l -> l.startsWith("worker") && l.contains("1m ago")), table);
        assertFalse(table.contains("Refused proofs"), "no footer when the proxy places every caller: " + table);
    }

    @Test
    void proxyActivityTableEndsWithWhatThePlacementCountersSay() {
        var bytes = new ByteArrayOutputStream();
        ProxyCommand.Activity.print(activity().placed(new dev.incusspawn.proxy.ProxyActivity.Placement(3, 2, true, List.of("idle"))),
                OutputFormat.TABLE, false, java.time.ZoneOffset.UTC,
                java.time.Instant.parse("2026-10-11T10:02:00Z"), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        var table = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(table.contains("Refused proofs: 3 matched no proof of their instance, 2 came from an address that is no instance"),
                table);
        assertTrue(table.contains("Running, no traffic attributed: idle"), table);
        assertTrue(table.contains("Instance listing failed"), table);
    }
}
