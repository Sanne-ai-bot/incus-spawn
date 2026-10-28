package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.util.RecordingOutput;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolsCommandTest {

    /** What {@code cmd}, which must succeed, printed as its answer. */
    private static String shown(BaseCommand cmd) {
        return String.join("\n", run(cmd, CommandResult.SUCCESS).resultLines());
    }

    /** Runs {@code cmd}, which must exit with {@code expected}, and returns what it reported. */
    private static RecordingOutput run(BaseCommand cmd, CommandResult expected) {
        return RecordingOutput.during(() -> assertEquals(expected, cmd.doExecute()));
    }

    @Test
    void listPrintsSortedToolNames() {
        var cmd = new ToolsCommand.ListSub();
        cmd.verbose = false;
        var lines = run(cmd, CommandResult.SUCCESS).resultLines();
        assertTrue(lines.size() >= 10, "should list at least 10 built-in tools");
        var sorted = lines.stream().sorted().toList();
        assertEquals(sorted, lines, "tool names must be sorted alphabetically");
    }

    @Test
    void listVerbosePrintsTable() {
        var cmd = new ToolsCommand.ListSub();
        cmd.verbose = true;
        var lines = run(cmd, CommandResult.SUCCESS).resultLines();
        var output = String.join("\n", lines);
        assertTrue(lines.getFirst().contains("NAME"), "header must contain NAME");
        assertTrue(lines.getFirst().contains("SOURCE"), "header must contain SOURCE");
        assertTrue(lines.getFirst().contains("DESCRIPTION"), "header must contain DESCRIPTION");
        assertTrue(output.contains("built-in"), "built-in tools must show 'built-in' source");
    }

    @Test
    void showPrintsToolDetails() {
        var cmd = new ToolsCommand.Show();
        cmd.name = "claude";
        var output = shown(cmd);
        assertTrue(output.contains("claude"), "must show tool name");
        assertTrue(output.contains("Description:"), "must show description");
        assertTrue(output.contains("Source:"), "must show source");
    }

    @Test
    void showPrintsYamlToolDownloads() {
        var cmd = new ToolsCommand.Show();
        cmd.name = "maven-3";
        var output = shown(cmd);
        assertTrue(output.contains("Downloads:"), "YAML tools must show downloads");
        assertTrue(output.contains("apache-maven"), "must show Maven download URL");
    }

    @Test
    void showPrintsParameters() {
        var cmd = new ToolsCommand.Show();
        cmd.name = "idea-backend";
        var output = shown(cmd);
        assertTrue(output.contains("Parameters:"), "must show parameters section");
        assertTrue(output.contains("memory"), "must show memory parameter");
        assertTrue(output.contains("Requires:"), "must show dependencies");
        assertTrue(output.contains("sshd"), "idea-backend requires sshd");
    }

    @Test
    void showPrintsYamlToolActions() {
        var cmd = new ToolsCommand.Show();
        cmd.name = "idea-backend";
        var output = shown(cmd);
        assertTrue(output.contains("Actions:"), "YAML tools must show their actions: " + output);
        assertTrue(output.contains("in Gateway (url)"), "must show the Gateway action: " + output);
    }

    @Test
    void recordListsYamlToolActions() {
        var tool = RuntimeServices.toolDefLoader().find("vscode-remote");
        var record = ToolsCommand.Show.record(tool, "built-in");
        assertEquals(List.of("Open repo '${repo_name}' in VS Code"), record.get("actions"));
    }

    @Test
    void showNotFoundReturnsError() {
        var cmd = new ToolsCommand.Show();
        cmd.name = "nonexistent-tool";
        var errOutput = String.join("\n", run(cmd, CommandResult.valueOf(1)).warnings());
        assertTrue(errOutput.contains("not found"), "must report tool not found");
        assertTrue(errOutput.contains("Available tools:"), "must list available tools");
    }

    @Test
    void bareToolsDelegatesToList() {
        var cmd = new ToolsCommand();
        var lines = run(cmd, CommandResult.SUCCESS).resultLines();
        assertTrue(lines.size() >= 10, "bare 'tools' must delegate to list");
    }
}
