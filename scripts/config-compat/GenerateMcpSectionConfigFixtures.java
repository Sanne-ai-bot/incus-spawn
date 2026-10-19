import java.nio.file.Path;
import java.util.List;

/**
 * The {@code mcp} fixture, for releases that kept the {@code isx mcp} policy in config.yaml's
 * {@code mcp:} section (v0.3.10 to v0.3.12): its {@code isx init} approving a template, as it saved it, and
 * a limit narrowed by hand. Later releases read the policy from {@code mcp.yaml} instead.
 *
 * <p>Args: output directory, file-name prefix (the release tag).
 */
public class GenerateMcpSectionConfigFixtures {

    public static void main(String[] args) throws Exception {
        GenerateConfigFixtures.write(Path.of(args[0]), args[1], "mcp", c -> {
            c.getGithub().setToken("ghp_fixture");
            var mcp = c.mcp();
            mcp.setTemplates(List.of("tpl-java"));
            mcp.setMaxInstances(4);
            c.setMcp(mcp);
        });
    }
}
