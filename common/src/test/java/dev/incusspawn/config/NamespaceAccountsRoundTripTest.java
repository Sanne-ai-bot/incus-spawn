package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A namespace's {@code accounts:} block must survive load-and-save.
 *
 * <p>Before {@link SpawnConfig.NamespaceConfig}, {@code GitHubConfig} and friends declared only
 * their flat fields and ignored everything else, so a hand-written {@code accounts:} block
 * deserialized to nothing and the next save <em>deleted</em> it -- destroying tokens, in the same
 * shape as the {@code putAccount} bug found in the review of the Claude account model. Namespaces
 * with no Java class were never at risk: they land in {@link SpawnConfig}'s own extras.
 */
class NamespaceAccountsRoundTripTest {


    private static String roundTrip(String yaml) throws Exception {
        return Jjq.toYaml(Jjq.spawnConfig(yaml));
    }

    @Test
    void githubAccountsSurviveASave() throws Exception {
        var out = roundTrip("""
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                    acme:
                      token: "ghp_acme"
                  default: acme
                """);
        assertTrue(out.contains("ghp_personal"), out);
        assertTrue(out.contains("ghp_acme"), out);
        assertTrue(out.contains("acme"), out);

        // And still resolve after the round trip.
        var reloaded = Jjq.spawnConfig(out);
        var tree = reloaded.tree();
        assertEquals("acme", AccountResolver.effectiveAccount(reloaded, "github", null));
        assertEquals("ghp_personal", dev.incusspawn.proxy.ToolProxyResolver
                .navigateConfigPath(tree, "github.accounts.personal.token"));
    }

    @Test
    void bobAndOpenaiAccountsSurviveASave() throws Exception {
        var out = roundTrip("""
                bob:
                  accounts:
                    work:
                      apiKey: "bob-work"
                  default: work
                openai:
                  accounts:
                    work:
                      apiKey: "sk-openai-work"
                  default: work
                """);
        assertTrue(out.contains("bob-work"), out);
        assertTrue(out.contains("sk-openai-work"), out);
    }

    @Test
    void flatFieldsStillRoundTripAlongsideAccounts() throws Exception {
        var out = roundTrip("""
                github:
                  token: "ghp_flat"
                  email: "me@example.com"
                  accounts:
                    acme:
                      token: "ghp_acme"
                """);
        assertTrue(out.contains("ghp_flat"), out);
        assertTrue(out.contains("me@example.com"), out);
        assertTrue(out.contains("ghp_acme"), out);
    }

    /**
     * The fix only holds for classes that opt in, so a new namespace class that forgets
     * {@code extends NamespaceConfig} would reintroduce credential deletion silently. Assert
     * the rule structurally rather than naming today's three by hand -- the same drift-check
     * shape as {@code NativeImageInitializationTest}.
     */
    @Test
    void everyNamespaceConfigClassPreservesUnknownKeys() {
        for (var nested : SpawnConfig.class.getDeclaredClasses()) {
            if (!java.lang.reflect.Modifier.isPublic(nested.getModifiers())) continue;
            if (nested.isEnum() || nested.isInterface() || nested.isRecord()) continue;
            if (nested == SpawnConfig.NamespaceConfig.class) continue;
            // An account, not a namespace: it keeps its undeclared keys itself
            // (claudeKeysIsxDoesNotDeclareSurviveASave)
            if (nested == SpawnConfig.ClaudeAccount.class) continue;
            assertTrue(SpawnConfig.NamespaceConfig.class.isAssignableFrom(nested),
                    nested.getSimpleName() + " must extend NamespaceConfig, or an accounts:"
                            + " block written into that namespace is deleted on the next save");
        }
    }

    /**
     * Claude's typed config declares {@code accounts} itself, which once let it skip
     * {@code NamespaceConfig}: a hand-written {@code claude.token}, or a stray key in an
     * account, was then dropped at bind and deleted by the next save, and the unread-key
     * check never saw it (#1876).
     */
    @Test
    void claudeKeysIsxDoesNotDeclareSurviveASave() throws Exception {
        var config = Jjq.spawnConfig("""
                claude:
                  token: sk-ant-x
                  accounts:
                    w: { type: api-key, apiKey: k, extra: y }
                """);
        var claude = config.tree().getField("claude");
        assertEquals("sk-ant-x", claude.getField("token").asText(), claude.toString());
        assertEquals("y", claude.getField("accounts").getField("w").getField("extra").asText(), claude.toString());

        // A write through the typed API leaves them, and the account still resolves
        config.getClaude().putAccount("v", SpawnConfig.ClaudeAccount.ofApiKey("k2"));
        var out = Jjq.toYaml(config);
        assertTrue(out.contains("token: sk-ant-x"), out);
        assertTrue(out.contains("extra: y"), out);
        var reloaded = Jjq.spawnConfig(out).getClaude();
        assertEquals("w", reloaded.accountName());
        assertEquals("k", reloaded.getApiKey());
    }

    /**
     * The map that holds undeclared keys is itself named {@code extras}; a key of that name in
     * the file is user data like any other, not a field the mapper knows.
     */
    @Test
    void aKeyNamedExtrasSurvivesASave() throws Exception {
        var out = roundTrip("""
                extras: top
                claude:
                  accounts:
                    w: { type: api-key, apiKey: k, extras: { a: 1 } }
                github:
                  extras: gh
                """);
        var reloaded = Jjq.spawnConfig(out).tree();
        assertEquals("top", reloaded.getField("extras").asText(), out);
        assertEquals("1", reloaded.getField("claude").getField("accounts").getField("w")
                .getField("extras").getField("a").asText(), out);
        assertEquals("gh", reloaded.getField("github").getField("extras").asText(), out);
    }

    /** Re-entering an account's credential keeps the keys isx does not declare in it. */
    @Test
    void rewritingAClaudeAccountKeepsItsUndeclaredKeys() throws Exception {
        var config = Jjq.spawnConfig("""
                claude:
                  accounts:
                    w: { type: api-key, apiKey: k, extra: y }
                """);
        config.getClaude().putAccount("w", SpawnConfig.ClaudeAccount.ofApiKey("k2"));
        var out = Jjq.toYaml(config);
        assertTrue(out.contains("extra: y"), out);
        assertEquals("k2", Jjq.spawnConfig(out).getClaude().getApiKey());
    }

    /** The redactor visits these for their undeclared keys, so none may be left out. */
    @Test
    void namespaceConfigsListsEveryTypedNamespace() throws Exception {
        var config = new SpawnConfig();
        var listed = config.namespaceConfigs();
        for (var field : SpawnConfig.class.getDeclaredFields()) {
            if (!SpawnConfig.NamespaceConfig.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            var value = field.get(config);
            assertTrue(listed.stream().anyMatch(n -> n == value),
                    field.getName() + " is missing from SpawnConfig.namespaceConfigs()");
        }
    }

    /** A namespace with no Java class at all was already safe; keep it that way. */
    @Test
    void unknownNamespaceAccountsSurviveASave() throws Exception {
        var out = roundTrip("""
                artifactory:
                  accounts:
                    acme:
                      token: "art-acme"
                  default: acme
                """);
        assertTrue(out.contains("art-acme"), out);
    }

}
