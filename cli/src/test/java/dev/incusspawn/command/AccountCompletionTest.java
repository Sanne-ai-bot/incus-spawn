package dev.incusspawn.command;

import dev.incusspawn.config.AccountSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code <namespace>=<account>} candidates shell completion offers: names, never credentials. */
@ExtendWith(IsolatedHome.class)
class AccountCompletionTest {

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
        var lines = AccountCommand.completionLines(config, AccountSelection.namespaceSetups(config));
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
        var lines = AccountCommand.completionLines(config, AccountSelection.namespaceSetups(config));
        assertEquals(java.util.List.of("claude=", "claude=acme\tdefault"), lines);

        config = IsolatedHome.seed("""
                claude:
                  accounts:
                    broken: {}
                """);
        assertEquals(java.util.List.of("claude="),
                AccountCommand.completionLines(config, AccountSelection.namespaceSetups(config)));
    }

    @Test
    void skipsNamespacesWithoutAccounts() throws Exception {
        var config = IsolatedHome.seed("claude: {}\n");
        assertEquals(java.util.List.of(),
                AccountCommand.completionLines(config, AccountSelection.namespaceSetups(config)));
    }
}
