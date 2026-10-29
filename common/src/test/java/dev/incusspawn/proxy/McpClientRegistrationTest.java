package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The guest half of #1182: the script every secret delivery runs registers {@code isx mcp} in
 * Claude Code when the host says the instance holds the grant, removes it when it does not, and
 * runs Claude Code only when that changes. Run in a real shell, with a recording {@code claude},
 * the guest's paths (its {@code /usr/local/bin} included, so a host's own {@code claude} or
 * {@code pi} stays out of it) moved under a temporary root, {@code runuser} (which needs root)
 * replaced by a recorder of what would run as the agent, and {@code timeout} (not on macOS)
 * left out.
 */
class McpClientRegistrationTest {

    @TempDir Path root;
    private Path home;
    private Path marker;
    private Path log;
    private Path asAgent;
    private Path agentRuns;

    @BeforeEach
    void guest() throws Exception {
        home = root.resolve("home/agentuser");
        marker = root.resolve("var/lib/isx/mcp-client-registered");
        log = home.resolve("claude.log");
        Files.createDirectories(home);
        // Stands in for runuser: records each command the script runs as the agent
        agentRuns = root.resolve("agent-runs.log");
        asAgent = root.resolve("as-agent");
        Files.writeString(asAgent, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> " + agentRuns + "\nexec \"$@\"\n");
        Files.setPosixFilePermissions(asAgent, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** Every command the script ran as the agent, as {@code env -i ... <command>}. */
    private List<String> agentRuns() throws Exception {
        return Files.exists(agentRuns) ? Files.readAllLines(agentRuns) : List.of();
    }

    /** A {@code claude} that records its arguments and keeps one user-scope entry, as the real one does. */
    private void installClaude() throws Exception {
        var bin = Files.createDirectories(home.resolve(".local/bin"));
        var claude = bin.resolve("claude");
        Files.writeString(claude, """
                #!/bin/sh
                printf '%s|%s\\n' "$*" "$(env | grep -c '^ISX_')" >> "$HOME/claude.log"
                case "$2" in
                  remove) [ -e "$HOME/registered" ] || exit 1; rm "$HOME/registered"; printf '{}' > "$HOME/.claude.json" ;;
                  add-json) [ -e "$HOME/registered" ] && exit 1; printf '%s' "$6" > "$HOME/registered"
                            printf '{"mcpServers":{"%s":%s}}' "$5" "$6" > "$HOME/.claude.json" ;;
                esac
                """);
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /**
     * A {@code pi} that records its arguments and keeps one entry in its user-level
     * {@code mcp.json}, as the real one does: {@code add} replaces, {@code remove} fails on none.
     */
    private void installPi() throws Exception {
        var bin = Files.createDirectories(home.resolve(".local/bin"));
        var pi = bin.resolve("pi");
        Files.writeString(pi, """
                #!/bin/sh
                printf '%s|%s\\n' "$*" "$(env | grep -c '^ISX_')" >> "$HOME/pi.log"
                mkdir -p "$HOME/.pi/agent"
                case "$2" in
                  remove) grep -q '"url"' "$HOME/.pi/agent/mcp.json" 2>/dev/null || exit 1
                          printf '{"mcpServers":{}}' > "$HOME/.pi/agent/mcp.json" ;;
                  add) printf '{"mcpServers":{"%s":{"url": "%s"}}}' "$3" "$5" > "$HOME/.pi/agent/mcp.json" ;;
                esac
                """);
        Files.setPosixFilePermissions(pi, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private List<String> piCalls() throws Exception {
        var piLog = home.resolve("pi.log");
        return Files.exists(piLog) ? Files.readAllLines(piLog) : List.of();
    }

    /** Pi's {@code pi mcp add} as the guest runs it, whose secret lives under {@link #root}. */
    private String piAdd() {
        return "mcp add isx " + String.join(" ", McpClientRegistration.PI_ADD_ARGS).replace("/run/isx", root + "/run/isx") + "|0";
    }

    private String script() throws Exception {
        return InstanceSecretTest.guestScriptUnder(root, "tmpfs /run tmpfs rw 0 0\n")
                .replace("/home/agentuser", home.toString())
                .replace("/var/lib/isx", root + "/var/lib/isx")
                .replace("/usr/local/bin", root + "/usr/local/bin")
                .replace("runuser -u agentuser -- ", asAgent + " ");
    }

    /** Whether this host has the guest's {@code timeout} (GNU coreutils; macOS has none). */
    static final boolean HAS_TIMEOUT = java.util.stream.Stream.of(System.getenv("PATH").split(":"))
            .anyMatch(d -> Files.isExecutable(Path.of(d, "timeout")));

    /**
     * The PATH a guest script runs with here: the host's, plus, where the host has no
     * {@code timeout}, one that runs its command unbounded -- tests that need the bound itself
     * assume {@link #HAS_TIMEOUT}.
     */
    static String guestPath(Path dir) throws Exception {
        if (HAS_TIMEOUT) return System.getenv("PATH");
        var shim = Files.createDirectories(dir).resolve("timeout");
        // Drops timeout's options and duration, then runs the command
        Files.writeString(shim, "#!/bin/sh\nwhile [ $# -gt 0 ]; do case $1 in -k) shift 2 ;; -*) shift ;;"
                + " *) shift; break ;; esac; done\nexec \"$@\"\n");
        Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwxr-xr-x"));
        return dir + ":" + System.getenv("PATH");
    }

    /** Run the delivery as an isx start would, telling the guest {@code grant}; null sends none. */
    private void start(String grant) throws Exception {
        var env = new HashMap<String, String>();
        if (grant != null) env.put(McpClientRegistration.ENV, grant);
        var pb = new ProcessBuilder("sh", "-c", script() + "\necho \"ready${" + McpClientRegistration.ENV + "}${isx_mcp}\"")
                .redirectErrorStream(true);
        pb.environment().putAll(env);
        pb.environment().put("PATH", guestPath(root.resolve("shim")));
        var run = pb.start();
        assertEquals("ready", new String(run.getInputStream().readAllBytes()).strip(),
                "the script answers, and leaves nothing of the grant behind");
        assertEquals(0, run.waitFor());
    }

    /** The entry as registered in this guest, whose secret lives under {@link #root}. */
    private String serverJson() {
        return McpClientRegistration.SERVER_JSON.replace("/run/isx", root + "/run/isx");
    }

    private List<String> calls() throws Exception {
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    @Test
    void aCoordinatorsFirstStartRegistersTheServer() throws Exception {
        installClaude();
        start("1");

        assertEquals(List.of("mcp remove --scope user isx|0",
                        "mcp add-json --scope user isx " + serverJson() + "|0"),
                calls(), "the official commands, as the agent, with nothing of isx's in their environment");
        assertEquals(serverJson(), Files.readString(home.resolve("registered")));
        assertTrue(Files.exists(marker));
    }

    @Test
    void aSteadyStateStartRunsNoClaudeCode() throws Exception {
        installClaude();
        start("1");
        Files.delete(log);

        start("1");
        assertEquals(List.of(), calls(), "a start that changes nothing costs one file test");
    }

    @Test
    void aCopysFirstStartRemovesTheRegistration() throws Exception {
        installClaude();
        start("1");
        Files.delete(log);

        // The copy's rootfs: registration and marker included, grant not
        start("0");
        assertEquals(List.of("mcp remove --scope user isx|0"), calls());
        assertFalse(Files.exists(home.resolve("registered")));
        assertFalse(Files.exists(marker));

        Files.delete(log);
        start("0");
        assertEquals(List.of(), calls());
    }

    @Test
    void onlyTheExactGrantSwitchesItOn() throws Exception {
        installClaude();
        for (var told : new String[] {null, "", "0", "true", "yes", "1 ", " 1", "11"}) {
            start(told);
        }
        assertEquals(List.of(), calls());
        assertFalse(Files.exists(marker));
    }

    @Test
    void aRegistrationMadeByHandIsReplacedByTheStandardOne() throws Exception {
        // The README's earlier recipe, which named the secret through the login profile
        installClaude();
        Files.writeString(home.resolve("registered"), "{\"type\":\"http\",\"url\":\"old\"}");

        start("1");
        assertEquals(serverJson(), Files.readString(home.resolve("registered")));
        assertTrue(Files.exists(marker));
    }

    @Test
    void anInstanceWithoutClaudeCodeIsLeftToTryAgain() throws Exception {
        assumeTrue(List.of("/usr/bin", "/bin").stream()
                .noneMatch(d -> Files.exists(Path.of(d, "claude"))), "a claude on the system PATH");
        start("1");
        assertFalse(Files.exists(marker), "nothing registered, so the next start tries again");

        installClaude();
        start("1");
        assertTrue(Files.exists(marker));
    }

    @Test
    void aFailedRegistrationIsTriedAgain() throws Exception {
        installClaude();
        Files.writeString(home.resolve(".local/bin/claude"), "#!/bin/sh\nexit 1\n");
        start("1");
        assertFalse(Files.exists(marker));
    }

    @Test
    void claudeCodeRunsAsTheAgentInACleanEnvironment() {
        // The harness above leaves runuser out (it needs root), so the guest's own form is
        // pinned here: root never runs the agent's claude, which the agent can replace
        var asAgent = "runuser -u agentuser -- env -i HOME=/home/agentuser ";
        assertTrue(McpClientRegistration.AS_AGENT.contains(asAgent), McpClientRegistration.AS_AGENT);
        var script = InstanceSecret.GUEST_SCRIPT;
        var withoutHelper = script.replace(McpClientRegistration.AS_AGENT, "");
        assertEquals(1, script.split(java.util.regex.Pattern.quote(McpClientRegistration.AS_AGENT), -1).length - 1,
                "one definition of isx_as_agent");
        // Every place claude is named is a call through isx_as_agent, or root's file test of a
        // path to it
        var named = java.util.regex.Pattern.compile("claude\\b(?!\\.json|-code|-calls)").matcher(withoutHelper);
        var calls = 0;
        while (named.find()) {
            var before = withoutHelper.substring(0, named.start());
            assertTrue(before.endsWith("isx_as_agent ") || before.matches("(?s).*\\[ -x /[^ ]*/$"),
                    "claude named outside isx_as_agent at: ..." + before.substring(Math.max(0, before.length() - 60)));
            calls++;
        }
        assertTrue(calls >= 3, "the script calls claude: " + calls);
        assertFalse(McpClientRegistration.GUEST_SCRIPT.contains("command -v"), "whether an agent is there is root's file test, not a process");
    }

    @Test
    void anEntryRemovedFromClaudeCodesConfigIsRegisteredAgain() throws Exception {
        // The marker says isx registered it; Claude Code's config, which is what it loads, says
        // it is gone -- the agent removed it, or something rewrote the file
        installClaude();
        start("1");
        Files.delete(home.resolve("registered"));
        Files.writeString(home.resolve(".claude.json"), "{\"projects\":{}}");
        Files.delete(log);

        start("1");
        assertEquals(List.of("mcp remove --scope user isx|0", "mcp add-json --scope user isx " + serverJson() + "|0"), calls());
        assertEquals(serverJson(), Files.readString(home.resolve("registered")));
    }

    /** Run {@link McpClientRegistration#CONFIG_HAS_ENTRY}, reading {@code read} where it tests {@code tested}. */
    private boolean configHasEntry(Path tested, Path read) throws Exception {
        var test = McpClientRegistration.CONFIG_HAS_ENTRY;
        var config = McpClientRegistration.CLAUDE_CONFIG;
        var at = test.indexOf(config);
        var script = test.substring(0, at) + tested + test.substring(at + config.length()).replace(config, read.toString());
        var pb = new ProcessBuilder("sh", "-c", script + " && echo yes || echo no").redirectErrorStream(true);
        pb.environment().put("PATH", guestPath(root.resolve("shim")));
        var run = pb.start();
        try {
            assertTrue(run.waitFor(McpClientRegistration.CONFIG_READ_SECONDS + 5, java.util.concurrent.TimeUnit.SECONDS),
                    "root's read of the agent's file must not hang the start");
            return new String(run.getInputStream().readAllBytes()).strip().equals("yes");
        } finally {
            run.descendants().forEach(ProcessHandle::destroyForcibly);
            run.destroyForcibly();
        }
    }

    @Test
    void theConfigIsReadOnlyAsMuchAndAsLongAsItMayBe() throws Exception {
        assumeTrue(HAS_TIMEOUT, "the bound is the guest's timeout");
        var config = home.resolve(".claude.json");
        Files.writeString(config, "{\"mcpServers\":{\"isx\":" + McpClientRegistration.SERVER_JSON + "}}");
        assertTrue(configHasEntry(config, config));

        // The agent swaps a FIFO in between the regular-file test and the read
        var fifo = root.resolve("fifo");
        new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor();
        assertFalse(configHasEntry(config, fifo));

        // Or a file larger than worth reading, the entry past the cap: read quickly, so it is
        // the size bound and not the time bound that answers
        var large = root.resolve("large.json");
        try (var out = Files.newOutputStream(large)) {
            var block = new byte[1 << 20];
            java.util.Arrays.fill(block, (byte) ' ');
            for (int i = 0; i <= McpClientRegistration.CONFIG_READ_BYTES >> 20; i++) out.write(block);
            out.write(("\"url\":\"https://" + ProxyConfig.MCP_DOMAIN + "/mcp\"").getBytes());
        }
        var began = System.nanoTime();
        assertFalse(configHasEntry(large, large));
        assertTrue(System.nanoTime() - began < java.util.concurrent.TimeUnit.SECONDS.toNanos(McpClientRegistration.CONFIG_READ_SECONDS),
                "answered by the size cap, not the timeout");
    }

    @Test
    void aCopyGetsOneRemovalAttempt() throws Exception {
        // One that fails (an entry claude cannot remove, a removal that timed out) must not
        // cost every later start; what is left reaches nothing, as the copy has no stamp
        installClaude();
        start("1");
        Files.writeString(home.resolve(".local/bin/claude"), "#!/bin/sh\necho \"$*\" >> \"$HOME/claude.log\"\nexit 1\n");
        Files.delete(log);

        start("0");
        assertFalse(Files.exists(marker));
        start("0");
        assertEquals(List.of("mcp remove --scope user isx"), calls(), "one remove, on the first start only");
    }

    @Test
    void aChangedEntryIsRegisteredAgain() throws Exception {
        // A coordinator registered by an isx whose entry differed holds that version's marker
        installClaude();
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "0123456789abcdef\n");
        Files.writeString(home.resolve("registered"), "{\"type\":\"http\",\"url\":\"older\"}");

        start("1");
        assertEquals(serverJson(), Files.readString(home.resolve("registered")));
        assertEquals(McpClientRegistration.ENTRY_VERSION + "\n", Files.readString(marker));
    }

    @Test
    void theHeadersHelperReadsTheSecretByItsPath() throws Exception {
        // Under a systemd unit there is no login profile, so no $ISX_INSTANCE_SECRET_FILE
        var secret = InstanceSecret.generate();
        var file = Files.createDirectories(root.resolve("run/isx")).resolve("instance-secret");
        Files.writeString(file, secret + "\n");
        var helper = McpClientRegistration.HEADERS_HELPER.replace(InstanceSecret.GUEST_PATH, file.toString());

        var pb = new ProcessBuilder("env", "-i", "sh", "-c", helper);
        var run = pb.start();
        var headers = JqValues.parse(new String(run.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, run.waitFor());
        assertEquals(secret, headers.path(InstanceSecret.HEADER).asText());
    }

    @Test
    void theServerIsTheProxysEndpoint() throws Exception {
        var entry = JqValues.parse(McpClientRegistration.SERVER_JSON);
        assertEquals("http", entry.path("type").asText());
        assertEquals("https://" + ProxyConfig.MCP_DOMAIN + "/mcp", entry.path("url").asText());
        assertEquals(McpClientRegistration.HEADERS_HELPER, entry.path("headersHelper").asText());
    }

    @Test
    void aCoordinatorsFirstStartRegistersTheServerInPi() throws Exception {
        // A Pi coordinator, with no Claude Code in the instance
        installPi();
        start("1");

        assertEquals(List.of(piAdd()), piCalls(),
                "Pi's own command, as the agent, with nothing of isx's in its environment");
        assertTrue(Files.readString(home.resolve(".pi/agent/mcp.json")).contains(ProxyConfig.MCP_DOMAIN + "/mcp\""));
        assertEquals(McpClientRegistration.PI_ENTRY_VERSION + "\n", Files.readString(piMarker()));
        assertFalse(Files.exists(marker), "Claude Code's marker is Claude Code's");

        Files.delete(home.resolve("pi.log"));
        start("1");
        assertEquals(List.of(), piCalls(), "a start that changes nothing costs one file test");
    }

    @Test
    void aCopysFirstStartRemovesThePiRegistration() throws Exception {
        installPi();
        start("1");
        Files.delete(home.resolve("pi.log"));

        start("0");
        assertEquals(List.of("mcp remove isx|0"), piCalls());
        assertFalse(Files.exists(piMarker()));

        Files.delete(home.resolve("pi.log"));
        start("0");
        assertEquals(List.of(), piCalls());
    }

    @Test
    void anEntryRemovedFromPisConfigIsRegisteredAgain() throws Exception {
        installPi();
        start("1");
        Files.writeString(home.resolve(".pi/agent/mcp.json"), "{\"mcpServers\":{}}");
        Files.delete(home.resolve("pi.log"));

        start("1");
        assertEquals(List.of(piAdd()), piCalls());
    }

    @Test
    void anInstanceWithBothAgentsRegistersTheServerInEach() throws Exception {
        installClaude();
        installPi();
        start("1");

        assertEquals(List.of("mcp remove --scope user isx|0", "mcp add-json --scope user isx " + serverJson() + "|0"), calls());
        assertEquals(List.of(piAdd()), piCalls());
        assertTrue(Files.exists(marker));
        assertTrue(Files.exists(piMarker()));
    }

    @Test
    void piRunsAsTheAgentInACleanEnvironment() {
        // As claude does: every place the script names pi is a call through isx_as_agent
        var withoutHelper = InstanceSecret.GUEST_SCRIPT.replace(McpClientRegistration.AS_AGENT, "");
        var named = java.util.regex.Pattern.compile("(?<![-/.\\w])pi\\b(?![-./])").matcher(withoutHelper);
        var calls = 0;
        while (named.find()) {
            var before = withoutHelper.substring(0, named.start());
            assertTrue(before.endsWith("isx_as_agent ") || before.matches("(?s).*\\[ -x /[^ ]*/$"),
                    "pi named outside isx_as_agent at: ..." + before.substring(Math.max(0, before.length() - 60)));
            calls++;
        }
        assertTrue(calls >= 2, "the script calls pi: " + calls);
    }

    @Test
    void aSteadyStateStartRunsNothingAsTheAgentWhicheverAgentsThereAre() throws Exception {
        // A coordinator with only Claude Code: Pi's marker never exists, so each start asks
        // whether Pi is there, which must not cost a process (runuser) on every start
        installClaude();
        start("1");
        Files.deleteIfExists(agentRuns);
        start("1");
        assertEquals(List.of(), agentRuns(), "Claude Code only");

        // Then with Pi too, once both are registered
        installPi();
        start("1");
        assertTrue(Files.exists(piMarker()));
        Files.deleteIfExists(agentRuns);
        start("1");
        assertEquals(List.of(), agentRuns(), "both agents");
    }

    @Test
    void anInstanceWithNeitherAgentRunsNothingAsTheAgent() throws Exception {
        assumeTrue(List.of("/usr/bin", "/bin").stream()
                .noneMatch(d -> Files.exists(Path.of(d, "claude")) || Files.exists(Path.of(d, "pi"))),
                "a claude or pi on the system PATH");
        start("1");
        start("1");
        start("0");
        assertEquals(List.of(), agentRuns(), "a coordinator or not, without an agent");
        assertFalse(Files.exists(marker));
        assertFalse(Files.exists(piMarker()));
    }

    @Test
    void piOnTheSystemPathIsFound() throws Exception {
        // Where the pi tool's npm install puts it, rather than the agent's own directory
        var bin = Files.createDirectories(root.resolve("usr/local/bin"));
        installPi();
        Files.move(home.resolve(".local/bin/pi"), bin.resolve("pi"));
        start("1");
        assertEquals(List.of(piAdd()), piCalls());
        assertTrue(Files.exists(piMarker()));
    }

    private Path piMarker() {
        return root.resolve("var/lib/isx/mcp-client-registered-pi");
    }
}
