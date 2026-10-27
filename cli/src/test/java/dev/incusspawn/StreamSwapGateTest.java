package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests read what code reported from the recording binding ({@code RecordingOutput}), never from
 * a swapped {@code System.out} or {@code System.err} (#1703): a swap catches bytes a terminal
 * would have drawn, not what was said or of which kind.
 *
 * <p>The test classes below still swap, each for the reason given: what they read is printed raw
 * today and moves to the seam in a later step of #1703, or the swap is what they test. A class not
 * listed may not swap, and a listed one that no longer does fails until it is dropped, so the list
 * only shrinks, as {@code raw-output-baseline.txt} does. A class that calls a swapping class's
 * static helper swaps too. It holds a class, not a count: a listed class's own swapping helper can
 * take a new caller, which review has to catch until the class is dropped.
 */
class StreamSwapGateTest {

    /** A call, a method reference, or a static import that would hide the calls after it. */
    private static final Pattern SWAP = Pattern.compile("\\bSystem\\s*(?:\\.|::)\\s*set(?:Out|Err)\\b"
            + "|\\bimport\\s+static\\s+java\\.lang\\.System\\.(?:setOut|setErr|\\*)\\s*;");

    private static final String CLI_COMMAND = "reads what cli/command prints raw (#1703, its step for cli/command)";
    private static final String INIT_FLOW = "reads what the init flow prints raw (#1703, its step for the init flow)";
    private static final String PROXY_LOG = "reads the proxy's log lines (#1703, its ProxyLog step)";

    /** Repository-relative test sources that may swap, each with why. */
    private static final Map<String, String> MAY_SWAP = Map.ofEntries(
            Map.entry("cli/src/test/java/dev/incusspawn/ExitCodeTest.java", "the entry point's streams and exit codes are what it tests"),
            Map.entry("cli/src/test/java/dev/incusspawn/TuiLaunchHandOverTest.java", "the pre-TUI hold watches the raw streams by design"),
            Map.entry("cli/src/test/java/dev/incusspawn/tui/PreTuiOutputTest.java", "the pre-TUI hold watches the raw streams by design"),
            Map.entry("cli/src/test/java/dev/incusspawn/command/QueryCommandFormatTest.java", "script output on stdout is the contract it tests (OutputFormat)"),
            Map.entry("common/src/test/java/dev/incusspawn/util/BuildOutputTest.java", "the terminal output's own rendering"),
            Map.entry("common/src/test/java/dev/incusspawn/util/OutputBindingTest.java", "that a binding writes no raw stream is what it tests"),
            Map.entry("common/src/test/java/dev/incusspawn/incus/IncusClientSmokeTest.java", "a guest's own output, passed through to the terminal"),
            Map.entry("cli/src/test/java/dev/incusspawn/command/AccountAddTest.java", INIT_FLOW),
            Map.entry("cli/src/test/java/dev/incusspawn/command/CredentialPromptsTest.java", INIT_FLOW),
            Map.entry("cli/src/test/java/dev/incusspawn/command/GitHubAuthFlowTest.java", INIT_FLOW),
            Map.entry("cli/src/test/java/dev/incusspawn/command/InitBannerTest.java", INIT_FLOW),
            Map.entry("cli/src/test/java/dev/incusspawn/command/InitStepOutputTest.java", INIT_FLOW),
            Map.entry("cli/src/test/java/dev/incusspawn/command/McpInitFlowTest.java", INIT_FLOW),
            // Through InitStepOutputTest.capture
            Map.entry("cli/src/test/java/dev/incusspawn/command/InitFirewallOutputTest.java", INIT_FLOW),
            Map.entry("cli/src/test/java/dev/incusspawn/command/MoveBridgeOffTest.java", INIT_FLOW),
            Map.entry("cli/src/test/java/dev/incusspawn/command/FollowBridgeMoveTest.java", INIT_FLOW),
            Map.entry("cli/src/test/java/dev/incusspawn/command/BuildCommandBatchTest.java", CLI_COMMAND),
            Map.entry("cli/src/test/java/dev/incusspawn/command/BuildCommandTest.java", CLI_COMMAND),
            Map.entry("cli/src/test/java/dev/incusspawn/command/BuildProgressTest.java", CLI_COMMAND),
            Map.entry("cli/src/test/java/dev/incusspawn/command/BuildToolsTest.java", CLI_COMMAND),
            Map.entry("cli/src/test/java/dev/incusspawn/command/CleanCommandTest.java", CLI_COMMAND),
            Map.entry("cli/src/test/java/dev/incusspawn/command/GuestProvisioningTest.java", CLI_COMMAND),
            Map.entry("cli/src/test/java/dev/incusspawn/command/RepoClonerReferenceTest.java", CLI_COMMAND),
            Map.entry("cli/src/test/java/dev/incusspawn/command/RepoClonerTest.java", CLI_COMMAND),
            Map.entry("cli/src/test/java/dev/incusspawn/command/ToolsCommandTest.java", CLI_COMMAND),
            Map.entry("common/src/test/java/dev/incusspawn/incus/ContainerTest.java", "reads a guest command's output, printed raw on failure"),
            Map.entry("common/src/test/java/dev/incusspawn/tool/NpmGlobalInstallTest.java", "reads a guest command's output, printed raw on failure"),
            Map.entry("common/src/test/java/dev/incusspawn/tool/YamlToolActionTest.java", "a raw prompt, and the stdin it must not read"),
            Map.entry("proxy/src/test/java/dev/incusspawn/proxy/MitmTlsTest.java", PROXY_LOG),
            Map.entry("proxy/src/test/java/dev/incusspawn/proxy/VerifiedArtifactStoreTest.java", PROXY_LOG),
            Map.entry("proxy/src/test/java/dev/incusspawn/proxy/WebSocketProxyTest.java", PROXY_LOG));

    @Test
    void onlyTheListedTestsSwapTheProcessStreams() throws IOException {
        var sources = RawOutputGateTest.sources("src/test/java");
        var swapping = new TreeSet<String>();
        sources.forEach((path, source) -> {
            // Only a source that names one can swap one; blanking the rest would change nothing
            if ((source.contains("setOut") || source.contains("setErr"))
                    && SWAP.matcher(RawOutputGateTest.blank(source)).find()) {
                swapping.add(path);
            }
        });
        // A class that calls a swapping class's static helper (InitStepOutputTest.capture) swaps too;
        // with no swapping class, an empty alternation would match every static call
        var swappers = "(?:" + String.join("|", swapping.stream()
                .map(path -> path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length())).toList()) + ")";
        var helpers = Pattern.compile("\\b" + swappers + "\\s*\\.\\s*[a-z]\\w*\\s*\\("
                + "|\\bimport\\s+static\\s+[\\w.]+\\." + swappers + "\\.\\w+\\s*;");
        if (!swapping.isEmpty()) {
            var direct = Set.copyOf(swapping);
            sources.forEach((path, source) -> {
                if (!direct.contains(path) && helpers.matcher(RawOutputGateTest.blank(source)).find()) swapping.add(path);
            });
        }
        var unlisted = new TreeSet<>(swapping);
        unlisted.removeAll(MAY_SWAP.keySet());
        assertEquals(new TreeSet<String>(), unlisted,
                "bind a RecordingOutput (common test-jar) instead of swapping System.out/System.err");
        var stale = new TreeMap<>(MAY_SWAP);
        stale.keySet().removeAll(swapping);
        assertEquals(Map.of(), stale, "these no longer swap a stream: drop them from MAY_SWAP");
    }
}
