import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.config.SpawnConfig;

import java.nio.file.Path;

/**
 * The {@code accounts} fixture, for releases with credential accounts (v0.3.9 on): what their
 * {@code isx init} writes, every credential under {@code accounts:}, through the calls
 * {@code CredentialSetup} makes. Two namespaces have a second account made the default, so the
 * {@code default:} pointer is pinned and not only the first account written.
 *
 * <p>Args: output directory, file-name prefix (the release tag).
 */
public class GenerateAccountConfigFixtures {

    public static void main(String[] args) throws Exception {
        GenerateConfigFixtures.write(Path.of(args[0]), args[1], "accounts", c -> {
            var claude = c.getClaude();
            claude.putAccount("default", SpawnConfig.ClaudeAccount.ofOauth("sk-ant-oat01-fixture"));
            claude.putAccount("work", SpawnConfig.ClaudeAccount.ofApiKey("sk-ant-api03-work"));
            claude.setDefaultAccount("work");
            NamespaceAccounts.put(c, "github", "default", "token", "ghp_fixture");
            NamespaceAccounts.put(c, "github", "default", "email", "me@example.com");
            NamespaceAccounts.put(c, "github", "work", "token", "ghp_work");
            NamespaceAccounts.put(c, "github", "work", "email", "me@work.example.com");
            NamespaceAccounts.put(c, "bob", "default", "apiKey", "bob-fixture");
            c.getBob().setLicenseConsent(true);
            NamespaceAccounts.put(c, "openai", "default", "apiKey", "sk-openai-fixture");
            NamespaceAccounts.put(c, "testProxyTool", "default", "token", "tpt-fixture");
            NamespaceAccounts.put(c, "testProxyTool", "work", "token", "tpt-work");
            NamespaceAccounts.setDefault(c, "testProxyTool", "work");
        });
    }
}
