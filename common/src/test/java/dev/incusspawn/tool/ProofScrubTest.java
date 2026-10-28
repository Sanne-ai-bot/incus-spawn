package dev.incusspawn.tool;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.proxy.ProofToken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every kind of proof isx issues is scrubbed from untrusted text, such as a build log a command
 * printed its environment into (#1742): one case per placeholder a built-in tool declares, so a
 * tool that adds a kind is covered without being listed here.
 */
class ProofScrubTest {

    private static final String SEED = "0123456789abcdef".repeat(4);

    @Test
    void everyBuiltInToolsProofIsScrubbed() throws Exception {
        var placeholders = new ArrayList<ProofToken.Placeholder>();
        for (var tool : RuntimeConstants.CDI_TOOLS) placeholders.addAll(tool.placeholders());
        for (var file : ToolDefLoader.BUILTIN_TOOLS) {
            try (var in = getClass().getClassLoader().getResourceAsStream("tools/" + file)) {
                placeholders.addAll(new YamlToolSetup(ToolDef.loadFromStream(in)).declaredPlaceholders());
            }
        }
        // The kinds the review named, so a refactor that loses one of them fails here
        var prefixes = placeholders.stream().map(ProofToken.Placeholder::prefix).toList();
        assertTrue(prefixes.containsAll(java.util.List.of("sk-ant-", "gho_", "ya29.")), prefixes.toString());
        assertTrue(placeholders.stream().anyMatch(p -> p.namespace().equals("typesafe")), placeholders.toString());
        assertTrue(placeholders.stream().anyMatch(p -> p.namespace().startsWith("atlassian")), placeholders.toString());

        var leaked = new ArrayList<String>();
        for (var placeholder : placeholders) {
            var proof = placeholder.tokenFor(SEED);
            var scrubbed = SecretRedactor.scrubText(placeholder.env() + "=" + proof + "\n", Map.of()).text();
            if (scrubbed.contains(proof.substring(proof.length() - 16)) || !scrubbed.contains("<isx:redacted:")) {
                leaked.add(placeholder.namespace() + " (" + placeholder.env() + "): " + scrubbed.strip());
            }
        }
        assertEquals(java.util.List.of(), leaked);
    }

    @Test
    void aStaticPlaceholderAndOtherHexAreLeftAlone() {
        var text = "GH_TOKEN=gho_placeholder commit 0123456789abcdef0123456789abcdef01234567";
        assertEquals(text, SecretRedactor.scrubText(text, Map.of()).text());
    }

    @Test
    void aLongLineOfTokenCharactersIsScrubbedInLinearTime() {
        // An open-ended prefix in the pattern made this quadratic: seconds per 50,000 characters
        var line = "A".repeat(500_000) + " gho_isx_" + "0123456789abcdef".repeat(2);
        var scrubbed = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> SecretRedactor.scrubText(line, Map.of()).text());
        assertTrue(scrubbed.endsWith(" gho_<isx:redacted:isx-proof>"), scrubbed.substring(scrubbed.length() - 40));
    }

    @Test
    void aProofRunningOnIntoMoreHexIsStillScrubbed() {
        // echo "$GH_TOKEN$(date +%s)": the digest is followed by digits, and must not survive
        var proof = "gho_isx_" + "0123456789abcdef".repeat(2);
        var scrubbed = SecretRedactor.scrubText(proof + "1730000000", Map.of()).text();
        assertFalse(scrubbed.contains("0123456789abcdef"), scrubbed);
    }
}
