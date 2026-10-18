package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates built-in tool definitions for common mistakes.
 */
class ToolDefValidationTest {

    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    private static final List<String> BUILTIN_TOOLS = ToolDefLoader.BUILTIN_TOOLS.stream()
            .map(f -> "tools/" + f).toList();

    @Test
    void allBuiltInToolsHaveValidSha256() throws IOException {
        var failures = new ArrayList<String>();

        for (var resourcePath : BUILTIN_TOOLS) {
            var tool = loadBuiltinTool(resourcePath);
            if (tool == null) {
                failures.add("Failed to load built-in tool: " + resourcePath);
                continue;
            }

            for (var download : tool.getDownloads()) {
                if (download.getSha256() == null) {
                    continue; // Optional field
                }

                var sha = download.getSha256();
                if (!SHA256_PATTERN.matcher(sha).matches()) {
                    failures.add(String.format(
                            "Tool '%s' has invalid SHA256 for %s: '%s' (must be 64 hex chars)",
                            tool.getName(), download.getUrl(), sha));
                }
            }
        }

        if (!failures.isEmpty()) {
            fail("Tool definition validation failures:\n  " + String.join("\n  ", failures));
        }
    }

    @Test
    void allBuiltInToolsLoadWithoutError() throws IOException {
        for (var resourcePath : BUILTIN_TOOLS) {
            var tool = loadBuiltinTool(resourcePath);
            assertNotNull(tool, "Failed to load: " + resourcePath);
            assertNotNull(tool.getName(), resourcePath + " missing name");
            assertFalse(tool.getName().isBlank(), resourcePath + " has blank name");
        }
    }

    /**
     * Since the base image ships no system bus, logind, and with it lingering and user units,
     * is opt-in per tool: one that relies on either brings the bus itself, as podman does (#1520).
     */
    @Test
    void builtInToolsThatNeedLogindBringASystemBus() throws IOException {
        var failures = new ArrayList<String>();
        for (var resourcePath : BUILTIN_TOOLS) {
            var tool = loadBuiltinTool(resourcePath);
            assertNotNull(tool, "Failed to load: " + resourcePath);
            var lingers = ToolDefValidator.buildSteps(tool)
                    .anyMatch(step -> step.contains("/var/lib/systemd/linger") || step.contains("enable-linger")
                            || step.contains(".config/systemd/user"))
                    || tool.getFiles().stream()
                            .anyMatch(f -> f.getPath().contains(".config/systemd/user"));
            if (lingers && !tool.getPackages().containsAll(List.of("dbus-broker", "systemd-pam"))) {
                failures.add(tool.getName() + " lingers or installs a user unit, but its packages lack dbus-broker or systemd-pam: "
                        + tool.getPackages());
            }
        }
        if (!failures.isEmpty()) fail(String.join("\n  ", failures));
    }

    @Test
    void allBuiltInToolsValidateWithoutWarnings() throws IOException {
        for (var resourcePath : BUILTIN_TOOLS) {
            var tool = loadBuiltinTool(resourcePath);
            assertNotNull(tool, "Failed to load: " + resourcePath);
            var errors = new ArrayList<String>();
            var warnings = new ArrayList<String>();
            ToolDefValidator.validateDef(tool, errors, warnings);
            assertEquals(List.of(), errors, resourcePath);
            assertEquals(List.of(), warnings, resourcePath);
        }
    }

    private ToolDef loadBuiltinTool(String resourcePath) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return ToolDef.loadFromStream(is);
        }
    }
}
