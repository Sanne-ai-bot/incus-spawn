package dev.incusspawn.mcp;

import dev.incusspawn.Environment;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.config.McpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The policy a session runs on is the host user's {@code mcp.yaml}, read on every call (#1331):
 * an {@code mcp:} section in {@code config.yaml} grants nothing, and a file that cannot be used
 * refuses the call instead of leaving an earlier approval standing.
 */
@ExtendWith(IsolatedHome.class)
class McpPolicyFileTest {

    private final TemplatePolicy policy = new TemplatePolicy(null, McpMain::policy);

    private static void write(String file, String yaml) throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.configDir().resolve(file), yaml);
    }

    private ToolError refusal(String template) {
        return assertThrows(ToolError.class, () -> policy.requireListed(template));
    }

    /**
     * Refused rather than defaults: those would lift the section's limits, start the orphan sweep
     * on default windows and release a coordinator's instances as unapproved.
     */
    @Test
    void aLegacySectionInConfigYamlAloneGrantsNothingAndRefusesUntilMoved() throws Exception {
        write("config.yaml", "mcp:\n  templates: [tpl-dev]\n  orphan-grace-hours: 168\n");
        var refused = refusal("tpl-dev");
        assertEquals(ToolError.Code.REFUSED, refused.code);
        assertTrue(refused.getMessage().contains("mcp.yaml does not exist, and the mcp: section of config.yaml"),
                refused.getMessage());
        assertEquals(ToolError.Code.REFUSED, assertThrows(ToolError.class, McpMain::policy).code);

        write("mcp.yaml", "templates: [tpl-java]\n");
        assertEquals(ToolError.Code.NOT_APPROVED, refusal("tpl-dev").code);
        assertEquals(McpConfig.DEFAULT_ORPHAN_GRACE_HOURS, McpMain.policy().orphanGraceHours());
    }

    /** SpawnConfig.load() reads a config.yaml that does not parse as empty, which would hide the section. */
    @Test
    void aConfigYamlThatDoesNotParseRefusesUntilMcpYamlExists() throws Exception {
        write("config.yaml", "mcp:\n  templates: [tpl-dev\n");
        var refused = refusal("tpl-dev");
        assertEquals(ToolError.Code.REFUSED, refused.code);
        assertTrue(refused.getMessage().contains("cannot be told; fix config.yaml or create mcp.yaml"), refused.getMessage());

        write("mcp.yaml", "templates: [tpl-dev]\n");
        policy.requireListed("tpl-dev");
    }

    @Test
    void aLegacySectionNeitherSupplementsNorOverridesMcpYaml() throws Exception {
        write("config.yaml", "mcp:\n  templates: [tpl-dev]\n  max-instances: 20\n");
        write("mcp.yaml", "templates: [tpl-java]\nmax-instances: 3\n");
        policy.requireListed("tpl-java");
        assertEquals(ToolError.Code.NOT_APPROVED, refusal("tpl-dev").code);
        assertEquals(3, McpMain.policy().maxInstances());
    }

    @Test
    void theNextCallSeesAnApprovalRemovedTheFileDeletedOrAnInvalidEdit() throws Exception {
        write("mcp.yaml", "templates: [tpl-dev, tpl-java]\n");
        policy.requireListed("tpl-dev");

        write("mcp.yaml", "templates: [tpl-java]\n");
        assertEquals(ToolError.Code.NOT_APPROVED, refusal("tpl-dev").code);
        policy.requireListed("tpl-java");

        Files.delete(McpConfig.file());
        assertEquals(ToolError.Code.NOT_APPROVED, refusal("tpl-java").code);
        assertEquals(List.of(), policy.approved());

        write("mcp.yaml", "templates: [tpl-java]\n");
        policy.requireListed("tpl-java");
        write("mcp.yaml", "templates: [tpl-java]\nmax-instances: many\n");
        var invalid = refusal("tpl-java");
        assertEquals(ToolError.Code.REFUSED, invalid.code);
        assertTrue(invalid.getMessage().contains("mcp.yaml:2: 'max-instances'"), invalid.getMessage());
        assertEquals(ToolError.Code.REFUSED, assertThrows(ToolError.class, policy::approved).code);
    }
}
