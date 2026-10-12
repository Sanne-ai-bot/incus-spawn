package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an inner proxy swaps for an instance that inherits a namespace (#1110), and where. */
class InheritedProofTest {

    private static final String PARENT = "11".repeat(32);
    private static final String INSTANCE = "22".repeat(32);
    private static final Map<String, List<ProofToken.Placeholder>> PREFIXES = InheritedProof.byNamespace(List.of(
            new ProofToken.Placeholder("GH_TOKEN", "gho_", "github"),
            new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude"),
            new ProofToken.Placeholder("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-", "claude"),
            new ProofToken.Placeholder("ISX_VERTEX_ACCESS_TOKEN", "ya29.", "claude")));

    @TempDir
    Path dir;
    private String original;

    @BeforeEach
    void pointAtTempSecret() {
        original = System.getProperty(InheritedProof.SECRET_FILE_PROPERTY);
        System.setProperty(InheritedProof.SECRET_FILE_PROPERTY, dir.resolve("instance-secret").toString());
    }

    @AfterEach
    void restore() {
        if (original == null) System.clearProperty(InheritedProof.SECRET_FILE_PROPERTY);
        else System.setProperty(InheritedProof.SECRET_FILE_PROPERTY, original);
    }

    private static String proof(String prefix, String secret, String namespace) {
        return prefix + ProofToken.MARKER + ProofToken.derive(secret, namespace);
    }

    @Test
    void eachClaudeCarrierKeepsItsPrefix() {
        var swap = InheritedProof.Swap.of(PARENT, Set.of("claude"), PREFIXES);
        for (var prefix : List.of("sk-ant-", "sk-ant-oat01-", "ya29.")) {
            assertEquals("Bearer " + proof(prefix, PARENT, "claude"),
                    swap.apply("Bearer " + proof(prefix, INSTANCE, "claude")), prefix);
        }
        assertEquals(proof("sk-ant-", PARENT, "claude"), swap.apply(proof("sk-ant-", INSTANCE, "claude")));
    }

    @Test
    void aBasicCredentialIsSwappedInsideItsEncoding() {
        var swap = InheritedProof.Swap.of(PARENT, Set.of("github"), PREFIXES);
        assertEquals(basic("x-access-token:" + proof("gho_", PARENT, "github")),
                swap.apply(basic("x-access-token:" + proof("gho_", INSTANCE, "github"))));
        assertEquals(basic("me:password"), swap.apply(basic("me:password")));
        assertEquals("Basic not-base64!", swap.apply("Basic not-base64!"));
    }

    private static String basic(String plain) {
        return "Basic " + Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void onlyAWholeProofOfAnInheritedNamespaceIsSwapped() {
        var swap = InheritedProof.Swap.of(PARENT, Set.of("github"), PREFIXES);
        var instanceProof = proof("gho_", INSTANCE, "github");
        // Static placeholders, real tokens, a longer token that merely contains a proof
        for (var value : List.of("token gho_placeholder", "token gho_realtoken123", "token x" + instanceProof,
                "token " + instanceProof + "0", "Bearer " + proof("sk-ant-", INSTANCE, "claude"))) {
            assertEquals(value, swap.apply(value));
        }
        assertNull(swap.apply(null));
    }

    @Test
    void theParentSecretIsReadWhenAsked() throws Exception {
        var file = dir.resolve("instance-secret");
        assertFalse(InheritedProof.nested());
        assertNull(InheritedProof.parentSecret());

        Files.writeString(file, "not a secret\n");
        assertTrue(InheritedProof.nested());
        assertNull(InheritedProof.parentSecret());

        Files.writeString(file, PARENT + "\n");
        assertEquals(PARENT, InheritedProof.parentSecret());
    }

    /** Inside an isx instance, what an instance may inherit is intercepted with nothing configured for it. */
    @Test
    void nestedTheDomainsOfInheritableCredentialsAreIntercepted() throws Exception {
        var config = Jjq.spawnConfig("{}");
        assertFalse(ToolProxyResolver.resolvedDomains(config).contains("github.com"));

        Files.writeString(dir.resolve("instance-secret"), PARENT + "\n");
        var domains = ToolProxyResolver.resolvedDomains(config);
        assertTrue(domains.containsAll(Set.of("github.com", "*.github.com")), domains.toString());
        // ... with no credential in them
        assertTrue(ToolProxyResolver.resolve(config, ToolProxyResolver.proxyToolSetups(config)).isEmpty());
    }

    @Test
    void choosingInheritOutsideAnIsxInstanceIsRefused() throws Exception {
        var config = Jjq.spawnConfig("{}");
        var inherit = Map.of("github", dev.incusspawn.config.AccountResolver.INHERIT);
        var e = assertThrows(dev.incusspawn.config.AccountResolver.UnknownAccountException.class,
                () -> dev.incusspawn.config.AccountSelection.validate(config, inherit));
        assertTrue(e.getMessage().contains("not running in an isx instance"), e.getMessage());

        Files.writeString(dir.resolve("instance-secret"), PARENT + "\n");
        dev.incusspawn.config.AccountSelection.validate(config, inherit);
    }

    @Test
    void placeholdersAreGroupedByNamespaceOncePerPrefix() {
        var gh = new ProofToken.Placeholder("GH_TOKEN", "gho_", "github");
        var byNamespace = InheritedProof.byNamespace(List.of(gh,
                new ProofToken.Placeholder("COPILOT_GITHUB_TOKEN", "gho_", "github"),
                new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude")));
        assertEquals(List.of(gh), byNamespace.get("github"));
        assertEquals(1, byNamespace.get("claude").size());
    }

    /** Two inherited namespaces declaring one prefix: either's proof could be meant, so neither is swapped. */
    @Test
    void aPrefixTwoInheritedNamespacesShareIsLeftAlone() {
        var placeholders = InheritedProof.byNamespace(List.of(
                new ProofToken.Placeholder("GH_TOKEN", "gho_", "github"),
                new ProofToken.Placeholder("GHE_TOKEN", "gho_", "ghe")));
        var proof = proof("gho_", INSTANCE, "ghe");
        assertEquals("token " + proof, InheritedProof.Swap.of(PARENT, List.of("github", "ghe"), placeholders)
                .apply("token " + proof));
        assertEquals("token " + proof("gho_", PARENT, "ghe"),
                InheritedProof.Swap.of(PARENT, List.of("ghe"), placeholders).apply("token " + proof));
    }

    /** Moving a GitHub pin to or from inherit is a change of identity, re-derived through the proxy. */
    @Test
    void inheritingGithubIsAnIdentityOfItsOwn() throws Exception {
        var config = Jjq.spawnConfig("github:\n  token: \"ghp_inner\"\n");
        assertEquals("inherit", new dev.incusspawn.tool.GhSetup().bakedAccountIdentity(config, "inherit"));
    }
}
