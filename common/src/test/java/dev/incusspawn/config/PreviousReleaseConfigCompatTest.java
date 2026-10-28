package dev.incusspawn.config;

import dev.incusspawn.proxy.ProxyCredentials;
import dev.incusspawn.proxy.ResolvedToolProxy;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Config files written by recent releases keep working, end to end.
 *
 * <p>The fixtures under {@code config-compat/} are not hand-written: they are what each
 * release's own {@code SpawnConfig.save()} produced (see
 * {@code scripts/config-compat/generate-fixtures.sh}), empty strings and stray
 * {@code oauthMode} keys included. They are written through the flat setters every release
 * has, so every namespace in them is flat: for v0.3.7 and v0.3.8, which predate credential
 * accounts, that is all they wrote; for v0.3.12 and v0.4.0 it is a file from before accounts
 * as they load and save it, which stays flat until a credential is written. Their expectations
 * are v0.3.8's: they differ from its files only in formatting -- no {@code ---}, empty strings,
 * {@code false} Vertex flag or stray {@code oauthMode}, so only v0.3.7 and v0.3.8 carry those.
 * v0.3.7 differs from v0.3.8 only in also writing the old single-path {@code host-path} key --
 * beside {@code host-paths} when one was set -- which is why it is kept: users are still on it.
 *
 * <p>Each is loaded the way the CLI and proxy load a real one -- {@link SpawnConfig#load()} from a
 * config directory, with a YAML tool installed beside it -- and followed through everything
 * accounts touch: listing, pin validation, the credentials the proxy would serve, and the first
 * write, which moves a flat credential into {@code accounts.default}. The claim throughout is
 * that nothing a user of that release had configured changes meaning. {@code
 * test-previous-release-config.sh} makes the same claim in CI against real instances and the
 * real proxy.
 *
 * <p>What only some releases write has fixtures of its own, in {@link #RELEASE_FIXTURES}: the
 * {@code accounts:} layout {@code isx init} writes since v0.3.9, the {@code mcp:} section of
 * v0.3.10 to v0.3.12,
 * which later releases refuse until it moves to {@code mcp.yaml}, and v0.4.0's hand-written
 * sections.
 */
class PreviousReleaseConfigCompatTest {

    /** Releases users are on whose files must keep working. Add one per format-changing release. */
    private static final List<String> RELEASES = List.of("v0.3.7", "v0.3.8", "v0.3.12", "v0.4.0");

    /** The releases whose {@code full} fixture sets {@code auto-clone-repos}, retired after them (#1789). */
    private static final List<String> WROTE_AUTO_CLONE_REPOS = List.of("v0.3.7", "v0.3.8", "v0.3.12", "v0.4.0");

    /** The fixtures every release has, as the generator names them. */
    private static final List<String> FIXTURES =
            List.of("full", "oauth", "vertex", "vertex-incomplete", "host-path", "empty");

    /**
     * Fixtures only some releases have, beyond {@link #FIXTURES}: what their own {@code isx init}
     * or a person writes that the flat setters cannot. Each has its tests below.
     */
    private static final Map<String, List<String>> RELEASE_FIXTURES = Map.of(
            "v0.3.12", List.of("accounts", "mcp"),
            "v0.4.0", List.of("accounts", "sections"));

    static Stream<String> releasesWith(String fixture) {
        return RELEASES.stream().filter(r -> RELEASE_FIXTURES.getOrDefault(r, List.of()).contains(fixture));
    }

    static Stream<String> releasesWithAccounts() {
        return releasesWith("accounts");
    }

    static Stream<String> releasesWithMcpSection() {
        return releasesWith("mcp");
    }

    static Stream<String> releasesWithSections() {
        return releasesWith("sections");
    }

    static Stream<Arguments> everyFixture() {
        return RELEASES.stream().flatMap(r -> FIXTURES.stream().map(f -> Arguments.of(r, f)));
    }

    /** Fixtures whose Claude setup is complete enough to rewrite as an account. */
    static Stream<Arguments> writableFixtures() {
        return everyFixture().filter(a -> !"vertex-incomplete".equals(a.get()[1]));
    }

    static Stream<String> releases() {
        return RELEASES.stream();
    }

    /** The fixture tool CI installs, so a namespace isx has no Java for is part of the file. */
    private static final String TEST_PROXY_TOOL = """
            name: test-proxy-tool
            proxy:
              config-namespace: testProxyTool
              configuration:
                token:
                  config-path: "token"
                  secret: true
              auth:
                - domains: [echo.incus-spawn.test]
                  type: bearer
                  token: "${token}"
            """;

    /** Every credential namespace in scope, in the order {@code isx account list} prints them. */
    private static final List<String> NAMESPACES =
            List.of("claude", "github", "bob", "openai", "testProxyTool");

    @TempDir
    Path home;
    private String savedHome;

    @BeforeEach
    void pointHomeAtATempDir() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", savedHome);
    }

    private SpawnConfig install(String release, String fixture) throws Exception {
        var configDir = home.resolve(".config/incus-spawn");
        Files.createDirectories(configDir.resolve("tools"));
        Files.writeString(configDir.resolve("tools/test-proxy-tool.yaml"), TEST_PROXY_TOOL);
        try (var in = getClass().getResourceAsStream("/config-compat/" + release + "-" + fixture + ".yaml")) {
            assertNotNull(in, "missing fixture " + release + "-" + fixture);
            Files.write(configDir.resolve("config.yaml"), in.readAllBytes());
        }
        return SpawnConfig.load();
    }

    private String configFile() throws Exception {
        return Files.readString(home.resolve(".config/incus-spawn/config.yaml"));
    }

    /** Which namespaces each fixture configured -- what that release would have served. */
    private static List<String> configured(String fixture) {
        return switch (fixture) {
            case "full" -> NAMESPACES;
            case "oauth" -> List.of("claude", "github");
            case "host-path" -> List.of("github");
            case "vertex", "vertex-incomplete" -> List.of("claude");
            case "empty" -> List.of();
            default -> throw new IllegalArgumentException(fixture);
        };
    }

    /**
     * Everything the proxy would inject for a selection, flattened for comparison: Claude's
     * credential, and every tool entry's resolved values per domain.
     */
    private static Map<String, String> served(SpawnConfig config, Map<String, String> selection) {
        var credentials = ProxyCredentials.forAccounts(config, selection);
        var served = new TreeMap<String, String>();
        served.put("claude.apiKey", credentials.anthropicApiKey());
        served.put("claude.oauthToken", credentials.oauthToken());
        served.put("claude.vertex", credentials.useVertex() + " " + credentials.vertexRegion()
                + " " + credentials.vertexProjectId());
        for (ResolvedToolProxy proxy : credentials.toolProxies()) {
            served.put(proxy.toolName() + "@" + proxy.domain(), new TreeMap<>(proxy.configValues()).toString());
        }
        return served;
    }

    private static Map<String, String> allDefault(List<String> namespaces) {
        var selection = new LinkedHashMap<String, String>();
        namespaces.forEach(ns -> selection.put(ns, "default"));
        return selection;
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyFixture")
    void everyConfiguredCredentialIsListedAsItsDefaultAccount(String release, String fixture) throws Exception {
        var config = install(release, fixture);
        for (var namespace : NAMESPACES) {
            var listing = AccountSelection.listAccounts(config, namespace);
            if (configured(fixture).contains(namespace)) {
                assertEquals(List.of("default"), listing.names(), fixture + ": " + namespace);
                assertEquals("default", listing.defaultName(), fixture + ": " + namespace);
            } else {
                assertEquals(List.of(), listing.names(), fixture + ": " + namespace
                        + " was not configured -- an empty string is not a credential");
            }
        }
    }

    /** The asymmetry #773 reported: {@code github=default} refused while {@code claude=default} was accepted. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyFixture")
    void pinningTheDefaultAccountIsAcceptedWhereverACredentialExists(String release, String fixture) throws Exception {
        var config = install(release, fixture);
        for (var namespace : NAMESPACES) {
            var pin = Map.of(namespace, "default");
            if (configured(fixture).contains(namespace)) {
                assertDoesNotThrow(() -> AccountSelection.validate(config, pin), fixture + ": " + namespace);
            } else {
                // Fails closed: there is no credential to pin, so pretending would serve nothing.
                assertThrows(AccountResolver.UnknownAccountException.class,
                        () -> AccountSelection.validate(config, pin), fixture + ": " + namespace);
            }
            assertThrows(AccountResolver.UnknownAccountException.class,
                    () -> AccountSelection.validate(config, Map.of(namespace, "work")),
                    fixture + ": only the flat credential's own name is pinnable");
        }
    }

    /** An instance pinned to {@code default} is served exactly what an unpinned one is. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyFixture")
    void aPinnedInstanceIsServedTheSameCredentialsAsAnUnpinnedOne(String release, String fixture) throws Exception {
        var config = install(release, fixture);
        assertEquals(served(config, Map.of()), served(config, allDefault(configured(fixture))), fixture);
    }

    /** What that release served, spelled out, so "the same as before" is not only relative. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("releases")
    void theFullFixtureServesWhatThePreviousReleaseDid(String release) throws Exception {
        var served = served(install(release, "full"), Map.of());
        assertEquals("sk-ant-api03-fixture", served.get("claude.apiKey"));
        assertEquals("{token=ghp_fixture}", served.get("gh@github.com"));
        assertEquals("{api-key=bob-fixture, license=true}", served.get("bob@bob.ibm.com"));
        assertEquals("{token=tpt-fixture}", served.get("test-proxy-tool@echo.incus-spawn.test"));
        assertTrue(served.values().stream().anyMatch(v -> v.contains("sk-openai-fixture")), served.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("releases")
    void everyNonCredentialSettingSurvivesLoading(String release) throws Exception {
        var config = install(release, "full");
        assertEquals(List.of("~/src"), config.getHostPaths());
        assertEquals(List.of("~/my-templates"), config.getSearchPaths());
        assertEquals(Map.of("quarkus", "~/quarkus"), config.getRepoPaths());
        assertEquals("10.0.0.1", config.getIncusBridgeGateway());
        // Retired (#1789): it loads as a key kept on save and never read.
        if (WROTE_AUTO_CLONE_REPOS.contains(release)) assertEquals("always", config.getExtras().get("auto-clone-repos"));
        assertTrue(config.isFeatureEnabled("openai"));
        assertTrue(config.getBob().isLicenseConsent());
    }

    /**
     * A flat {@code useVertex: true} with no region is a misconfiguration the proxy reports by
     * name. It must still arrive as a Vertex account, not be filtered into "no credentials".
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("releases")
    void anUnfinishedVertexSetupStillReachesTheProxyAsVertex(String release) throws Exception {
        var config = install(release, "vertex-incomplete");
        assertTrue(config.getClaude().hasAuth());
        var credentials = ProxyCredentials.forAccounts(config, Map.of("claude", "default"));
        assertTrue(credentials.useVertex());
        assertEquals("", credentials.vertexRegion());
    }

    /**
     * The first write moves each flat credential into {@code accounts.default}. Nothing a user
     * had configured may change meaning across it: the proxy serves the same values before and
     * after, from the file as saved and reloaded.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("writableFixtures")
    void theFirstWriteMovesFlatCredentialsIntoAccountsWithoutChangingWhatIsServed(String release, String fixture)
            throws Exception {
        var config = install(release, fixture);
        var before = served(config, Map.of());

        // What 'isx init' does when it saves: Claude through its typed API, every other
        // namespace through NamespaceAccounts, which materializes the flat credential first.
        var claude = config.getClaude();
        if (claude.hasAuth()) claude.putAccount(claude.accountName(), claude.account());
        for (var namespace : NAMESPACES) {
            if (!namespace.equals("claude")) NamespaceAccounts.materialize(config, namespace);
        }
        config.save();

        var saved = configFile();
        var reloaded = SpawnConfig.load();
        assertEquals(before, served(reloaded, Map.of()), fixture + ":\n" + saved);
        for (var namespace : configured(fixture)) {
            assertTrue(AccountResolver.hasAccountsBlock(reloaded.tree(), namespace), namespace + ":\n" + saved);
            assertEquals(List.of("default"), AccountResolver.accountNames(reloaded, namespace));
            assertEquals("default", AccountResolver.defaultName(reloaded.tree(), namespace));
        }
        assertFalse(saved.contains("oauthMode"), "the phantom key is not carried forward:\n" + saved);
        var raw = JqYaml.parse(saved);
        for (var namespace : NAMESPACES) {
            var node = raw.path(namespace);
            if (node.isObject()) node.objectValue().forEach((key, value) -> assertFalse(
                    value.isString() && value.asText().isEmpty(),
                    namespace + "." + key + " was left as an empty string:\n" + saved));
        }
        if (fixture.equals("full")) {
            assertTrue(reloaded.getBob().isLicenseConsent(), "consent is shared, not moved:\n" + saved);
            assertTrue(reloaded.getOpenai().hasAuth(), "a key under accounts: still counts");
            assertTrue(reloaded.isFeatureEnabled("openai"));
            assertEquals("me@example.com", AccountResolver.value(reloaded, "github", "default", "email"));
            assertEquals("", AccountResolver.navigate(reloaded.tree(), "github.email"),
                    "the email belongs to the identity and moves with it:\n" + saved);
        }
    }

    /** Adding a second account to a previous-release file keeps the first one as the default. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("releases")
    void addingAnAccountKeepsThePreviousCredentialAsTheDefault(String release) throws Exception {
        var config = install(release, "full");
        var before = served(config, Map.of());
        NamespaceAccounts.put(config, "github", "work", "token", "ghp_work");
        NamespaceAccounts.put(config, "bob", "work", "apiKey", "bob-work");
        NamespaceAccounts.put(config, "testProxyTool", "work", "token", "tpt-work");
        config.getClaude().putAccount("work", SpawnConfig.ClaudeAccount.ofApiKey("sk-ant-api03-work"));
        config.save();

        var reloaded = SpawnConfig.load();
        assertEquals(before, served(reloaded, Map.of()), "nothing unpinned may move to the new account");
        var work = served(reloaded, Map.of("github", "work", "bob", "work", "testProxyTool", "work",
                "claude", "work"));
        assertEquals("sk-ant-api03-work", work.get("claude.apiKey"));
        assertEquals("{token=ghp_work}", work.get("gh@github.com"));
        assertEquals("{api-key=bob-work, license=true}", work.get("bob@bob.ibm.com"),
                "the new Bob account inherits the shared consent");
        assertEquals("{token=tpt-work}", work.get("test-proxy-tool@echo.incus-spawn.test"));
    }

    /**
     * The old single-path key. v0.3.7 wrote it beside {@code host-paths} naming the same path;
     * it must load as one host path, not two, and the next save must not carry it forward.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("releases")
    void theOldHostPathKeyLoadsAsOneHostPathAndIsNotWrittenBack(String release) throws Exception {
        var config = install(release, "host-path");
        assertEquals(List.of("~/src"), config.getHostPaths());
        config.save();
        var saved = configFile();
        assertFalse(saved.contains("host-path:"), saved);
        assertEquals(List.of("~/src"), SpawnConfig.load().getHostPaths());
    }

    /**
     * A committed fixture not declared here would be loaded by no test, and a release-specific
     * one for a release not in {@link #RELEASES} would run none of its tests.
     */
    @Test
    void everyCommittedFixtureIsDeclaredForAListedRelease() throws Exception {
        assertTrue(RELEASES.containsAll(RELEASE_FIXTURES.keySet()), RELEASE_FIXTURES.keySet().toString());
        var expected = RELEASES.stream()
                .flatMap(r -> Stream.concat(FIXTURES.stream(), RELEASE_FIXTURES.getOrDefault(r, List.of()).stream())
                        .map(f -> r + "-" + f + ".yaml"))
                .collect(Collectors.toCollection(TreeSet::new));
        // The source tree, not the classpath copy, which keeps a deleted fixture until a clean build.
        var dir = Path.of("src/test/resources/config-compat");
        try (var files = Files.list(dir)) {
            assertEquals(expected, files.map(f -> f.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new)));
        }
    }

    /**
     * What {@code isx init} writes since accounts: every credential under {@code accounts:},
     * Claude and testProxyTool with a second account made the default. Listing, the default
     * pointer and what each pin serves are as that release had them, and a save keeps them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("releasesWithAccounts")
    void accountsAndTheirDefaultsAreListedAndServedAsWritten(String release) throws Exception {
        var config = install(release, "accounts");
        assertAccountsAsWritten(config);
        var before = servedByPin(config);
        config.save();
        var reloaded = SpawnConfig.load();
        assertAccountsAsWritten(reloaded);
        assertEquals(before, servedByPin(reloaded), configFile());
    }

    private static Map<String, Map<String, String>> servedByPin(SpawnConfig config) {
        return Map.of(
                "unpinned", served(config, Map.of()),
                "all default", served(config, allDefault(NAMESPACES)),
                "all work", served(config, Map.of("claude", "work", "github", "work", "testProxyTool", "work")));
    }

    private static void assertAccountsAsWritten(SpawnConfig config) {
        var expected = Map.of(
                "claude", List.of("default", "work"),
                "github", List.of("default", "work"),
                "bob", List.of("default"),
                "openai", List.of("default"),
                "testProxyTool", List.of("default", "work"));
        var defaults = Map.of("claude", "work", "github", "default", "bob", "default",
                "openai", "default", "testProxyTool", "work");
        for (var namespace : NAMESPACES) {
            var listing = AccountSelection.listAccounts(config, namespace);
            assertEquals(expected.get(namespace), listing.names(), namespace);
            assertEquals(defaults.get(namespace), listing.defaultName(), namespace);
        }
        assertThrows(AccountResolver.UnknownAccountException.class,
                () -> AccountSelection.validate(config, Map.of("bob", "work")));

        var unpinned = served(config, Map.of());
        assertEquals("sk-ant-api03-work", unpinned.get("claude.apiKey"), "Claude's default is work");
        assertEquals("{token=ghp_fixture}", unpinned.get("gh@github.com"));
        assertEquals("{api-key=bob-fixture, license=true}", unpinned.get("bob@bob.ibm.com"));
        assertEquals("{token=tpt-work}", unpinned.get("test-proxy-tool@echo.incus-spawn.test"));
        assertTrue(unpinned.values().stream().anyMatch(v -> v.contains("sk-openai-fixture")), unpinned.toString());

        var pinned = served(config, Map.of("claude", "default", "github", "work", "testProxyTool", "default"));
        assertEquals("sk-ant-oat01-fixture", pinned.get("claude.oauthToken"));
        assertEquals("{token=ghp_work}", pinned.get("gh@github.com"));
        assertEquals("{token=tpt-fixture}", pinned.get("test-proxy-tool@echo.incus-spawn.test"));
        assertEquals("me@work.example.com", AccountResolver.value(config, "github", "work", "email"),
                "the email is per account");
    }

    /**
     * v0.3.12 kept the {@code isx mcp} policy in config.yaml's {@code mcp:} section, which
     * {@code isx init} wrote when approving a template. Since v0.4.0 the policy is
     * {@code mcp.yaml}, and the section is refused, not silently ignored: without the file
     * {@link McpConfig#load()} fails until the user moves it, since the defaults could widen what
     * they wrote (#1447). The rest of the file loads and is served as before, and a save keeps
     * the section, so nothing the user wrote is lost before they move it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("releasesWithMcpSection")
    void anMcpSectionInConfigYamlIsRefusedUntilMovedToMcpYaml(String release) throws Exception {
        var config = install(release, "mcp");
        assertEquals("{token=ghp_fixture}", served(config, Map.of()).get("gh@github.com"));
        assertTrue(config.hasLegacyMcpSection());
        assertNotNull(McpConfig.legacySectionNotice(config));
        var refused = assertThrows(McpConfig.Invalid.class, McpConfig::load);
        assertTrue(refused.getMessage().contains("mcp.yaml"), refused.getMessage());

        config.save();
        var saved = configFile();
        assertTrue(SpawnConfig.load().hasLegacyMcpSection(), "a save keeps the section:\n" + saved);
        assertTrue(saved.contains("tpl-java"), saved);

        // Once moved, the file is the policy; the section is not read.
        Files.writeString(McpConfig.file(), "templates: [tpl-dev]\n");
        var mcp = McpConfig.load();
        assertEquals(List.of("tpl-dev"), mcp.templates());
        assertEquals(McpConfig.DEFAULT_MAX_INSTANCES, mcp.maxInstances(), "not the section's 4");
    }

    /** Sections a person adds by hand, as v0.4.0 saved them, and a Gemini account. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("releasesWithSections")
    void handWrittenSectionsAndAGeminiAccountSurviveLoadingAndSaving(String release) throws Exception {
        var config = install(release, "sections");
        assertSectionsAsWritten(config);
        config.save();
        assertSectionsAsWritten(SpawnConfig.load());
    }

    private void assertSectionsAsWritten(SpawnConfig config) throws Exception {
        var file = configFile();
        assertTrue(config.hostCa().propagate(), file);
        var timeouts = config.readyTimeouts();
        assertNotNull(timeouts, file);
        var resolved = timeouts.resolve();
        assertEquals(Duration.ofSeconds(90), resolved.container(), file);
        assertEquals(Duration.ofMinutes(4), resolved.vm(), file);
        assertEquals(45, timeouts.proxySeconds(30), file);
        assertEquals(1024L << 20, config.gitCache().snapshotBytes(), file);
        assertEquals(Duration.ofDays(3), config.gitCache().maxAge(), file);
        assertEquals(List.of("default"), AccountSelection.listAccounts(config, "gemini").names(), file);
        assertEquals("gemini-fixture", AccountResolver.value(config, "gemini", "default", "apiKey"), file);
        var served = served(config, Map.of());
        assertTrue(served.values().stream().anyMatch(v -> v.contains("gemini-fixture")), served.toString());
        assertEquals("{token=ghp_fixture}", served.get("gh@github.com"));
    }
}
