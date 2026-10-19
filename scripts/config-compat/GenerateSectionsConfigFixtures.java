import dev.incusspawn.config.NamespaceAccounts;

import java.nio.file.Path;

/**
 * The {@code sections} fixture, for releases with {@code git-cache:} (v0.4.0 on): the sections a
 * person adds by hand, as the release's next save leaves them, and a Gemini account.
 *
 * <p>Args: output directory, file-name prefix (the release tag).
 */
public class GenerateSectionsConfigFixtures {

    private static final String HAND_EDITED = """
            host-ca:
              propagate: true
            ready-timeouts:
              container: 90s
              vm: 4m
              proxy: 45s
            git-cache:
              snapshot-max-mib: 1024
              max-age-days: 3
            """;

    public static void main(String[] args) throws Exception {
        GenerateConfigFixtures.write(Path.of(args[0]), args[1], "sections", HAND_EDITED, c -> {
            NamespaceAccounts.put(c, "github", "default", "token", "ghp_fixture");
            NamespaceAccounts.put(c, "gemini", "default", "apiKey", "gemini-fixture");
        });
    }
}
