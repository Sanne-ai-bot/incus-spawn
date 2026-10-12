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
}
