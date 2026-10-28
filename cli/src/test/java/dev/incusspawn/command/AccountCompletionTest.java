package dev.incusspawn.command;

import dev.incusspawn.config.AccountSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code <namespace>=<account>} candidates shell completion offers: names, never credentials. */
@ExtendWith(IsolatedHome.class)
class AccountCompletionTest {

    private static java.util.List<String> lines(dev.incusspawn.config.SpawnConfig config) {
        return AccountCommand.completionLines(config, AccountSelection.namespaceSetups(config));
    }

    /** The {@code --account} / {@code account set} candidates: the lines holding an "=". */
    private static java.util.List<String> selections(dev.incusspawn.config.SpawnConfig config) {
        return lines(config).stream().filter(l -> l.contains("=")).toList();
    }

    @Test
    void offersNamespacesAndAccountNamesButNeverCredentials() throws Exception {
        var config = IsolatedHome.seed("""
                github:
                  accounts:
                    personal:
                      token: "xxxxxxxxxxxx"
                    acme:
                      token: "yyyyyyyyyyyy"
                """);
        var lines = lines(config);
        assertTrue(lines.contains("github="), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("github=personal")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("github=acme")), lines.toString());
        assertEquals(1, lines.stream().filter(l -> l.endsWith("\tdefault")).count(), lines.toString());
        var all = String.join("\n", lines);
        assertFalse(all.contains("xxxx") || all.contains("yyyy"), lines.toString());
    }

    /**
     * Only what {@code --account} and {@code account set} accept: an incomplete account is left
     * out, but its namespace stays, so {@code account unset} can still name it.
     */
    @Test
    void leavesOutIncompleteAccounts() throws Exception {
        var config = IsolatedHome.seed("""
                claude:
                  accounts:
                    acme:
                      type: vertex
                      cloudMlRegion: europe-west1
                      vertexProjectId: acme-prod
                    broken: {}
                """);
        assertEquals(java.util.List.of("claude=", "claude=acme\tdefault"), selections(config));

        // No usable account: nothing to select, not even a bare "claude=" that could not be completed.
        config = IsolatedHome.seed("""
                claude:
                  accounts:
                    broken: {}
                """);
        assertEquals(java.util.List.of(), selections(config));
        assertTrue(lines(config).contains("claude"), "unset can still name it: " + lines(config));
    }

    /** Every namespace is offered to unset, even one with no accounts left that an instance may still pin. */
    @Test
    void unsetCandidatesAreEveryNamespace() throws Exception {
        var config = IsolatedHome.seed("claude: {}\n");
        assertEquals(java.util.List.of(), selections(config));
        var namespaces = lines(config);
        assertTrue(namespaces.contains("claude") && namespaces.contains("github"), namespaces.toString());
    }

    @Test
    void skipsNamesThatHoldAControlCharacter() throws Exception {
        var config = IsolatedHome.seed("""
                github:
                  accounts:
                    "bad\\tname":
                      token: "xxxxxxxxxxxx"
                    good:
                      token: "yyyyyyyyyyyy"
                """);
        // The skipped one is the default here, so no line carries the marker.
        assertEquals(java.util.List.of("github=", "github=good"), selections(config));
    }

    /** account add takes a namespace or its tool, so both are offered, and only what it accepts (#1773). */
    @Test
    void addCandidatesAreTheNamespacesAndToolsAddAccepts() {
        var tools = new dev.incusspawn.tool.ToolDefLoader().allToolSetups();
        var lines = AccountCommand.addCompletionLines(tools);
        assertTrue(lines.containsAll(java.util.List.of("claude", "gh", "github")), lines.toString());
        assertEquals(lines.stream().sorted().distinct().toList(), lines, "sorted, once each");
        for (var name : lines) {
            assertNotNull(dev.incusspawn.config.CredentialCheck.credentialTool(name, tools), name);
        }
        assertFalse(lines.contains("maven-3"), "a tool without a proxy: " + lines);
        assertFalse(lines.contains("copilot"), "a tool that borrows another's credential: " + lines);
    }

    /**
     * A user tool with a credential step, named {@code name}, beside the built-in tools. A cloned
     * repository's {@code .incus-spawn/tools} can name a tool the same way.
     */
    static java.util.Map<String, dev.incusspawn.tool.ToolSetup> toolsWithCredentialStep(String... names)
            throws java.io.IOException {
        var dir = java.nio.file.Files.createDirectories(dev.incusspawn.Environment.configDir().resolve("tools"));
        for (int i = 0; i < names.length; i++) {
            java.nio.file.Files.writeString(dir.resolve("tool" + i + ".yaml"), credentialToolYaml(names[i], i));
        }
        return new dev.incusspawn.tool.ToolDefLoader().allToolSetups();
    }

    /**
     * {@link #toolsWithCredentialStep} with a tool named {@code hostile} added as is. The loader
     * refuses such a name (#1778), so this is how a listing's own check is tested.
     */
    static java.util.Map<String, dev.incusspawn.tool.ToolSetup> withHostileTool(String hostile, String... names)
            throws java.io.IOException {
        var tools = new java.util.LinkedHashMap<>(toolsWithCredentialStep(names));
        var def = dev.incusspawn.tool.ToolDef.loadFromStream(new java.io.ByteArrayInputStream(
                credentialToolYaml(hostile, names.length).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        tools.put(hostile, new dev.incusspawn.tool.YamlToolSetup(def));
        return tools;
    }

    private static String credentialToolYaml(String name, int i) {
        return """
                name: "%s"
                description: "A tool with a credential step"
                proxy:
                  config-namespace: ns%d
                  configuration:
                    api-key:
                      config-path: apiKey
                      description: "API key"
                      secret: true
                  auth:
                    - domains:
                        - api.example.com
                      type: bearer
                      token: "${api-key}"
                """.formatted(name, i);
    }

    /** A name the shell would expand is never printed: bash's compgen -W would run it on TAB. */
    @Test
    void addCandidatesLeaveOutNamesTheShellWouldExpand() throws Exception {
        assertFalse(toolsWithCredentialStep("$(touch pwned)").containsKey("$(touch pwned)"), "refused at load");
        var tools = withHostileTool("$(touch pwned)", "good-tool");
        var lines = AccountCommand.addCompletionLines(tools);
        assertTrue(lines.contains("good-tool"), lines.toString());
        assertFalse(lines.stream().anyMatch(l -> l.contains("touch")), lines.toString());
    }
}
