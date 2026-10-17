package dev.incusspawn.config;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.proxy.InheritedProof;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pre-flight credential check answers for the accounts the instance will actually use --
 * whatever pinned them -- and for every credential its tools declare (#793).
 */
class CredentialCheckTest {


    /** Built-in tools only, so no tool YAML on the developer's machine can leak in. */
    private static Map<String, ToolSetup> tools(ToolSetup... extra) {
        var map = new LinkedHashMap<String, ToolSetup>();
        RuntimeConstants.CDI_TOOLS.forEach(t -> map.put(t.name(), t));
        for (var t : extra) map.put(t.name(), t);
        return map;
    }

    private static String check(String configYaml, String templateYaml, Map<String, String> selection,
                                ToolSetup... extraTools) throws Exception {
        var config = Jjq.spawnConfig(configYaml);
        var all = tools(extraTools);
        return CredentialCheck.check(config, ImageDef.parseYaml(templateYaml), Map.of(), selection, all, all);
    }

    private static String check(String configYaml, String templateYaml) throws Exception {
        return check(configYaml, templateYaml, Map.of());
    }

    private static String template(String tools) {
        return "name: tpl\ntools: " + tools + "\n";
    }

    private static String piTemplate(String provider) {
        return "name: tpl-pi\ntools:\n  - pi: {provider: " + provider + "}\n";
    }

    /** {@code <ns>} accounts 'personal' (the default) and 'work', with {@code key} only in {@code keyed}. */
    private static String keyOnlyIn(String ns, String key, String keyed) {
        var other = keyed.equals("work") ? "personal" : "work";
        return """
                %s:
                  accounts:
                    %s: { %s: "secret-%s" }
                    %s: {}
                  default: personal
                """.formatted(ns, keyed, key, keyed, other);
    }

    // --- pi + OpenAI: the case #793 reported

    @Test
    void piOpenaiPinnedToConfiguredAccountIsNotMissing() throws Exception {
        assertEquals("", check(keyOnlyIn("openai", "apiKey", "work"), piTemplate("openai"),
                Map.of("openai", "work")));
    }

    @Test
    void piOpenaiPinnedToAccountWithoutKeyIsMissing() throws Exception {
        var result = check(keyOnlyIn("openai", "apiKey", "personal"), piTemplate("openai"),
                Map.of("openai", "work"));
        assertTrue(result.contains("OpenAI API key"), result);
    }

    @Test
    void piOpenaiUnpinnedUsesDefault() throws Exception {
        assertEquals("", check(keyOnlyIn("openai", "apiKey", "personal"), piTemplate("openai")));
    }

    // --- Declared secrets, account-aware

    @Test
    void codexGhAndBobAnswerForThePinnedAccount() throws Exception {
        var config = keyOnlyIn("openai", "apiKey", "personal")
                + keyOnlyIn("github", "token", "personal")
                + keyOnlyIn("bob", "apiKey", "personal");
        var pinned = Map.of("openai", "work", "github", "work", "bob", "work");
        var result = check(config, template("[codex, gh, bob]"), pinned);
        assertTrue(result.contains("OpenAI API key"), result);
        assertTrue(result.contains("GitHub personal access token"), result);
        assertTrue(result.contains("IBM Bob API key"), result);
        assertEquals("", check(config, template("[codex, gh, bob]")));
    }

    @Test
    void copilotNeedsTheGithubTokenItBorrows() throws Exception {
        var result = check("{}", template("[copilot]"));
        assertTrue(result.contains("GitHub"), result);
        assertEquals("", check("github: { token: ghp_x }", template("[copilot]")));
    }

    @Test
    void aSharedSecretIsReportedOnce() throws Exception {
        var result = check("{}", template("[gh, copilot]"));
        assertEquals(1, result.split("GitHub", -1).length - 1, result);
    }

    @Test
    void aToolsRequirementsAreCheckedToo() throws Exception {
        var wrapper = new FakeTool("my-agent", "codex");
        var result = check("{}", template("[my-agent]"), Map.of(), wrapper);
        assertTrue(result.contains("OpenAI API key"), result);
    }

    @Test
    void theWholeTemplateChainIsChecked() throws Exception {
        var config = Jjq.spawnConfig("{}");
        var parent = ImageDef.parseYaml("name: tpl-base\ntools: [gh]\n");
        var child = ImageDef.parseYaml("name: tpl-leaf\nparent: tpl-base\ntools: [podman]\n");
        var all = tools();
        var result = CredentialCheck.check(config, child, Map.of("tpl-base", parent), Map.of(), all, all);
        assertTrue(result.contains("GitHub"), result);
    }

    @Test
    void eachMissingCredentialNamesTheTemplatesThatNeedIt() throws Exception {
        var config = Jjq.spawnConfig("{}");
        var defs = new LinkedHashMap<String, ImageDef>();
        for (var yaml : List.of("name: tpl-base\n", "name: tpl-dev\nparent: tpl-base\ntools: [gh]\n",
                "name: tpl-agent\nparent: tpl-dev\ntools: [codex]\n",
                "name: tpl-typo\nparent: tpl-dev\naccounts: {github: nope}\n")) {
            var def = ImageDef.parseYaml(yaml);
            defs.put(def.getName(), def);
        }
        var all = tools();
        var missing = CredentialCheck.missingByCredential(config, defs.keySet(), defs, all, all);
        // tpl-typo's unknown account is the build's to refuse, not a missing credential
        assertEquals(Map.of(
                new CredentialCheck.Missing("GitHub personal access token", "isx account add github"),
                List.of("tpl-dev", "tpl-agent"),
                new CredentialCheck.Missing("OpenAI API key", "isx account add openai"),
                List.of("tpl-agent")), missing);
    }

    // --- The remedy names the one credential's command, not the whole of 'isx init' (#1469)

    @Test
    void theRefusalNamesTheCommandThatAddsTheMissingCredential() throws Exception {
        assertEquals("Missing credentials: GitHub personal access token."
                + " Run 'isx account add github' to configure it.", check("{}", template("[gh]")));
    }

    @Test
    void eachMissingCredentialGetsItsOwnCommand() throws Exception {
        var result = check("{}", template("[claude, gh]"));
        assertTrue(result.endsWith(" Run 'isx account add claude' and 'isx account add github'"
                + " to configure them."), result);
    }

    /** Copilot borrows GitHub's token, so the command is GitHub's: Copilot has no step of its own. */
    @Test
    void aBorrowedCredentialNamesTheNamespaceThatOwnsIt() throws Exception {
        var result = check("{}", template("[copilot]"));
        assertTrue(result.endsWith(" Run 'isx account add github' to configure it."), result);
    }

    @Test
    void piNamesTheCredentialItsProviderSpends() throws Exception {
        assertTrue(check("{}", template("[pi]")).endsWith("'isx account add claude' to configure it."));
        assertTrue(check("{}", piTemplate("openai")).endsWith("'isx account add openai' to configure it."));
    }

    @Test
    void aYamlToolsCredentialIsAddedByItsNamespace() throws Exception {
        var result = check("{}", template("[three-ways]"), Map.of(), yamlTool(THREE_WAYS));
        assertTrue(result.endsWith(" Run 'isx account add threeWays' to configure them."), result);
    }

    /** A secret outside any namespace, declared by a tool with a step of its own: that step sets it. */
    @Test
    void aCredentialOutsideANamespaceNamesTheToolThatDeclaresIt() throws Exception {
        var result = check("{}", template("[borrower]"), Map.of(), yamlTool(BORROWER));
        assertEquals("Missing credentials: Borrowed key. Run 'isx account add borrower' to configure it.", result);
    }

    private static final String BORROWER = """
            name: borrower
            proxy:
              configuration:
                key: { config-path: elsewhere.key, description: "Borrowed key", secret: true }
              auth:
                - { domains: [e.example], type: bearer, token: "${key}" }
            """;

    /** No step owns the secret, and the tool declaring it has none either: init's menu is the way. */
    @Test
    void aCredentialNoStepOwnsFallsBackToInit() throws Exception {
        var tool = new NoStep(yamlTool(BORROWER));
        var result = check("{}", template("[borrower]"), Map.of(), tool);
        assertEquals("Missing credentials: Borrowed key. Run 'isx init' to configure it.", result);
    }

    /** {@code delegate}'s proxy, but borrowed, as Copilot's is: no step of its own. */
    private record NoStep(ToolSetup delegate) implements ToolSetup {
        @Override public String name() { return delegate.name(); }
        @Override public dev.incusspawn.tool.ToolDef.ProxyDef proxy() { return delegate.proxy(); }
        @Override public boolean hasOwnCredentials() { return false; }
        @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) { }
    }

    /**
     * A YAML tool named after gh's namespace must not take over gh's remedy: {@code isx account
     * add github} still runs gh's step, which is the one that sets the missing token.
     */
    @Test
    void aToolNamedAfterAnotherNamespaceDoesNotTakeItOver() throws Exception {
        var impostor = yamlTool("""
                name: github
                proxy:
                  config-namespace: ghe
                  configuration:
                    token: { config-path: token, description: "GHE token", secret: true }
                  auth:
                    - { domains: [ghe.example], type: bearer, token: "${token}" }
                """);
        var all = tools(impostor);
        assertEquals("gh", CredentialCheck.credentialTool("github", all));
        assertEquals("github", CredentialCheck.credentialTool("ghe", all));
        assertEquals("Missing credentials: GitHub personal access token."
                + " Run 'isx account add github' to configure it.", check("{}", template("[gh]"), Map.of(), impostor));
        assertEquals("isx account add ghe", CredentialCheck.stepCommand("github", all));
        assertEquals("isx account add github", CredentialCheck.stepCommand("gh", all));
        // Its own secret: its namespace, not its name, which is gh's
        assertEquals("Missing credentials: GHE token. Run 'isx account add ghe' to configure it.",
                check("{}", template("[github]"), Map.of(), impostor));
    }

    /**
     * A tool declaring a key in a namespace another tool's step owns: that step never asks for the
     * key, so the declaring tool's step is named.
     */
    @Test
    void aKeyTheNamespaceOwnersStepDoesNotAskForNamesTheDeclaringTool() throws Exception {
        var aider = yamlTool("""
                name: aider
                proxy:
                  config-namespace: openai
                  configuration:
                    org: { config-path: org, description: "OpenAI organization", secret: true }
                  auth:
                    - { domains: [org.example], type: header, name: X-Org, value: "${org}" }
                """);
        // The owner codex's key is served in the same namespace, so it is asked for too
        assertEquals("Missing credentials: OpenAI organization, OpenAI API key."
                        + " Run 'isx account add aider' and 'isx account add openai' to configure them.",
                check("{}", template("[aider]"), Map.of(), aider));
        // Its step's own skip message names the same command, and codex's names its namespace
        var all = tools(aider);
        assertEquals("isx account add aider", CredentialCheck.stepCommand("aider", all));
        assertEquals("isx account add openai", CredentialCheck.stepCommand("codex", all));
        // codex's own key still goes to the namespace codex owns
        assertTrue(check("{}", template("[codex]"), Map.of(), aider).endsWith("'isx account add openai' to configure it."));
    }

    @Test
    void theStepIsFoundByNamespaceOrByTool() {
        var all = tools();
        assertEquals("gh", CredentialCheck.credentialTool("github", all));
        assertEquals("gh", CredentialCheck.credentialTool("gh", all));
        assertEquals("claude", CredentialCheck.credentialTool("claude", all));
        assertEquals("codex", CredentialCheck.credentialTool("openai", all));
        // Copilot spends GitHub's token and has no step; pi and podman have no credential at all
        assertNull(CredentialCheck.credentialTool("copilot", all));
        assertNull(CredentialCheck.credentialTool("pi", all));
        assertNull(CredentialCheck.credentialTool("podman", all));
        assertNull(CredentialCheck.credentialTool("nope", all));
    }

    // --- Claude, and pi on Claude's credential

    private static final String CLAUDE_API_KEY_AND_VERTEX = """
            claude:
              accounts:
                personal: { type: api-key, apiKey: "sk-ant-x" }
                work: { type: vertex, cloudMlRegion: us-east5, vertexProjectId: proj }
              default: personal
            """;

    @Test
    void piVertexPinnedToVertexAccountIsNotMissing() throws Exception {
        assertEquals("", check(CLAUDE_API_KEY_AND_VERTEX, piTemplate("vertex"), Map.of("claude", "work")));
    }

    @Test
    void piVertexUnpinnedWithNonVertexDefaultIsMissing() throws Exception {
        var result = check(CLAUDE_API_KEY_AND_VERTEX, piTemplate("vertex"));
        assertTrue(result.contains("Vertex AI configuration"), result);
    }

    @Test
    void piDefaultProviderNeedsClaude() throws Exception {
        var result = check("{}", template("[pi]"));
        assertTrue(result.contains("Anthropic API key"), result);
    }

    @Test
    void piProviderIsxDoesNotManageNeedsNothingFromIt() throws Exception {
        assertEquals("", check("{}", piTemplate("openrouter")));
    }

    @Test
    void claudeWithNoAccountIsMissing() throws Exception {
        var result = check("{}", template("[claude]"));
        assertTrue(result.contains("Anthropic API key"), result);
    }

    @Test
    void anIncompleteLegacyVertexSetupIsMissing() throws Exception {
        var legacy = "claude: { useVertex: true }";
        assertTrue(check(legacy, template("[claude]")).contains("Anthropic API key"));
        assertTrue(check(legacy, piTemplate("vertex")).contains("Vertex AI configuration"));
    }

    @Test
    void aStaleSelectionIsReportedNotThrown() throws Exception {
        var result = check(CLAUDE_API_KEY_AND_VERTEX, template("[claude]"), Map.of("claude", "nope"));
        assertTrue(result.contains("nope"), result);
    }

    private record FakeTool(String name, String requirement) implements ToolSetup {
        @Override public List<String> requires() { return List.of(requirement); }
        @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) { }
    }

    @Test
    void piWithAnEmptyProviderTakesItsDefault() throws Exception {
        // As the build resolves it: a null provider is the default, anthropic, not a crash.
        var result = check("{}", "name: tpl-pi\ntools:\n  - pi: {provider: ~}\n");
        assertTrue(result.contains("Anthropic API key"), result);
    }

    // --- Only what the proxy needs: an auth entry is served once the keys it references resolve

    private static dev.incusspawn.tool.ToolSetup yamlTool(String yaml) throws Exception {
        return new dev.incusspawn.tool.YamlToolSetup(dev.incusspawn.tool.ToolDef.loadFromStream(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /** Three auth entries on three domains, each with its own secret, as the CI fixture has. */
    private static final String THREE_WAYS = """
            name: three-ways
            proxy:
              config-namespace: threeWays
              configuration:
                token: { config-path: token, description: "Three-ways token", secret: true }
                password: { config-path: password, description: "Three-ways password", secret: true }
                api-key: { config-path: apiKey, description: "Three-ways API key", secret: true }
              auth:
                - { domains: [a.example], type: bearer, token: "${token}" }
                - { domains: [b.example], type: basic, username: "u", password: "${password}" }
                - { domains: [c.example], type: header, name: X-Key, value: "Key ${api-key}" }
            """;

    @Test
    void oneServableAuthEntryIsEnough() throws Exception {
        // The proxy injects the bearer token; the other two secrets serve other domains.
        assertEquals("", check("threeWays: { token: t }", template("[three-ways]"), Map.of(),
                yamlTool(THREE_WAYS)));
    }

    @Test
    void noServableAuthEntryNamesWhatWouldServeOne() throws Exception {
        var result = check("{}", template("[three-ways]"), Map.of(), yamlTool(THREE_WAYS));
        assertTrue(result.contains("Three-ways token"), result);
        assertTrue(result.contains("Three-ways password"), result);
    }

    @Test
    void aSecretNobodyCouldSetIsNotAskedFor() throws Exception {
        // No config-path and no value: the validator only warns, and no config.yaml could hold it.
        var tool = yamlTool("""
                name: unsettable
                proxy:
                  configuration:
                    key: { description: "Unsettable key", secret: true }
                  auth:
                    - { domains: [d.example], type: bearer, token: "${key}" }
                """);
        assertEquals("", check("{}", template("[unsettable]"), Map.of(), tool));
    }

    // --- inherit (#1110): the parent instance's credential, wherever this isx runs in one

    @Test
    void anInheritedCredentialNeedsNoneHereWhenNested(@TempDir Path dir) throws Throwable {
        withSecretFile(dir, true, () -> {
            assertEquals("", check("{}", template("[gh]"), Map.of("github", "inherit")));
            assertEquals("", check("github:\n  token: \"ghp_flat\"\n  default: inherit\n", template("[gh]")));
            assertEquals("", check("{}", template("[claude]"), Map.of("claude", "inherit")));
            assertEquals("", check("claude:\n  default: inherit\n", template("[claude]")));
        });
    }

    @Test
    void anInheritedCredentialOutsideAnIsxInstanceIsMissing(@TempDir Path dir) throws Throwable {
        withSecretFile(dir, false, () -> {
            for (var result : List.of(check("{}", template("[gh]"), Map.of("github", "inherit")),
                    check("claude:\n  default: inherit\n", template("[claude]")))) {
                assertTrue(result.contains("not running in an isx instance"), result);
            }
        });
    }

    private static void withSecretFile(Path dir, boolean nested, Executable check) throws Throwable {
        var property = InheritedProof.SECRET_FILE_PROPERTY;
        var original = System.getProperty(property);
        var file = dir.resolve("instance-secret");
        if (nested) Files.writeString(file, "11".repeat(32) + "\n");
        System.setProperty(property, file.toString());
        try {
            check.execute();
        } finally {
            if (original == null) System.clearProperty(property);
            else System.setProperty(property, original);
        }
    }

    @Test
    void piOnVertexInheritingClaudeNeedsNoneHereWhenNested(@TempDir Path dir) throws Throwable {
        withSecretFile(dir, true, () ->
                assertEquals("", check("{}", piTemplate("vertex"), Map.of("claude", "inherit"))));
    }
}
