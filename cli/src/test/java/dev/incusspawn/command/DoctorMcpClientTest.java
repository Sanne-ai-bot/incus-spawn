package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #1812: {@code isx doctor} says where a coordinator template's {@code coordinator.control-over} and
 * {@code mcp.yaml} disagree, with the remedy {@code isx branch} and {@code isx mcp} give.
 */
@ExtendWith(IsolatedHome.class)
class DoctorMcpClientTest {

    private static ImageDef coordinator(String name, List<String> controls) throws Exception {
        var def = ImageDef.parseYaml("name: " + name + "\nmcp-client:\n  default: true\n"
                + (controls == null ? "" : "  templates: " + controls + "\n"));
        def.setSource("/home/u/.config/incus-spawn/images/" + name + ".yaml");
        return def;
    }

    @Test
    void eachMismatchIsAFindingWithItsRemedy() throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.configDir().resolve("mcp.yaml"), "templates: [tpl-a]\n");
        var defs = Map.of("tpl-x", coordinator("tpl-x", List.of("tpl-a", "tpl-b")), "tpl-y", coordinator("tpl-y", null),
                "tpl-ok", coordinator("tpl-ok", List.of("tpl-a")));
        var findings = DoctorCommand.mcpClientFindings(defs);
        assertEquals(List.of(
                "Coordinator template tpl-x|tpl-b is in coordinator.control-over of tpl-x (/home/u/.config/incus-spawn/images/tpl-x.yaml)"
                        + " but not approved for agents. Add it to templates in ~/.config/incus-spawn/mcp.yaml, or remove it"
                        + " from coordinator.control-over there.",
                "Coordinator template tpl-y|tpl-y declares no coordinator.control-over, so its coordinators control nothing."
                        + " List the templates they may control under coordinator.control-over in tpl-y"
                        + " (/home/u/.config/incus-spawn/images/tpl-y.yaml)."),
                findings.stream().map(f -> f.label() + "|" + f.detail()).toList());
        assertEquals(List.of(), DoctorCommand.mcpClientFindings(Map.of("tpl-plain", ImageDef.parseYaml("name: tpl-plain\n"))));
    }
}
