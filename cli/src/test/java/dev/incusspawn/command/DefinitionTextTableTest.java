package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Definition text a project-local template or tool ships is untrusted (#765), so the human
 * tables show it through {@code OutputFormat.oneLine}, as {@code --format=plain} does (#1133):
 * an ESC, C1 CSI, DEL, line break or bidi override never reaches the terminal raw.
 */
class DefinitionTextTableTest {

    /** ESC, a C1 CSI (U+009B), DEL, a newline, a bidi override and isolate, a line separator. */
    private static final String HOSTILE = "a\u001b[2J\u009b2J\u007fb\nc\u202ed\u2066e\u2028f";

    @Test
    void templatesTableShowsDefinitionTextOnOneSafeLine() throws Exception {
        var def = ImageDef.parseYaml("name: tpl-evil\ndescription: x\n");
        def.setDescription(HOSTILE);
        def.setSource("/tmp/p\u001b]0;t\u0007/.incus-spawn/images/tpl-evil.yaml");
        var defs = Map.of("tpl\u001b[31m-evil", def);

        assertSafe(print(out -> TemplatesCommand.ListSub.printTable(out, defs, true)), 2);
        assertSafe(print(out -> TemplatesCommand.ListSub.printTable(out, defs, false)), 1);
    }

    @Test
    void toolsTableShowsDefinitionTextOnOneSafeLine() {
        Map<String, ToolSetup> tools = Map.of("tool\u001b[31m", tool());

        assertSafe(print(out -> ToolsCommand.ListSub.printTable(out, tools, name -> "/tmp/p\u009b2J", true)), 2);
        assertSafe(print(out -> ToolsCommand.ListSub.printTable(out, tools, name -> "/tmp/p", false)), 1);
    }

    @Test
    void toolsShowShowsDefinitionTextOnSafeLines() {
        var output = print(out -> ToolsCommand.Show.print(out, tool(), "/tmp/p\u001b[2J"));
        assertSafe(output, output.lines().count());
        assertTrue(output.contains("  Description:  a [2J 2J b c d e f"), output);
    }

    private static ToolSetup tool() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer\u001b[2J");
        auth.setDomains(List.of("api.example.com\u001b[2J"));
        var proxy = new ToolDef.ProxyDef();
        proxy.setAuth(List.of(auth));
        return new ToolSetup() {
            @Override public String name() { return "tool\u001b[31m"; }
            @Override public String description() { return HOSTILE; }
            @Override public String feature() { return "gate\u009b2J"; }
            @Override public List<String> requires() { return List.of("dep\u001b[2J"); }
            @Override public List<String> packages() { return List.of("pkg\u001b[2J"); }
            @Override public ToolDef.ProxyDef proxy() { return proxy; }
            @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) { }
        };
    }

    private static String print(Consumer<PrintStream> printer) {
        var bytes = new ByteArrayOutputStream();
        printer.accept(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** {@code lines} lines, and nothing a terminal acts on but their line ends. */
    private static void assertSafe(String output, long lines) {
        assertEquals(lines, output.lines().count(), output);
        output.lines().forEach(line -> assertFalse(line.codePoints().anyMatch(c ->
                c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x2028 || c == 0x2029
                        || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)),
                "control character in: " + line));
    }
}
