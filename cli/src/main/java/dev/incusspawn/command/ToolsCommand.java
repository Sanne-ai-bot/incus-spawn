package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

@CommandDefinition(
        name = "tools",
        description = "List and inspect available tool definitions",
        generateHelp = true,
        groupCommands = {
                ToolsCommand.ListSub.class,
                ToolsCommand.Show.class
        }
)
public class ToolsCommand extends BaseCommand {

    // Bare, the command is its list, so it takes list's --format.
    @Option(name = "format", description = "Output format: table (default), plain or json")
    String format;

    @Override
    protected CommandResult doExecute() throws Exception {
        var list = new ListSub();
        list.format = format;
        return list.doExecute();
    }

    // ── list ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "list", description = "List available tools",
            generateHelp = true)
    public static class ListSub extends BaseCommand {

        @Option(shortName = 'v', name = "verbose", description = "Show source and description",
                hasValue = false)
        boolean verbose;

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", description = "Output format: table (default), plain or json")
        String format;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            var loader = RuntimeServices.toolDefLoader();
            var tools = new TreeMap<>(loader.allToolSetups());
            if (outputFormat != OutputFormat.TABLE) {
                outputFormat.print(System.out, records(tools, loader::getSource));
                return CommandResult.SUCCESS;
            }
            printTable(System.out, tools, loader::getSource, verbose);
            return CommandResult.SUCCESS;
        }

        /** The {@code table} format: the names, or with {@code verbose} a name, source and description per row. */
        static void printTable(PrintStream out, Map<String, ToolSetup> tools,
                               UnaryOperator<String> sourceOf, boolean verbose) {
            if (!verbose) {
                tools.keySet().forEach(name -> out.println(cell(name)));
                return;
            }
            int maxName = tools.keySet().stream().mapToInt(String::length).max().orElse(10);
            int maxSource = tools.keySet().stream()
                    .mapToInt(name -> sourceOf.apply(name).length())
                    .max().orElse(7);
            var fmt = "%-" + maxName + "s  %-" + maxSource + "s  %s%n";
            out.printf(fmt, "NAME", "SOURCE", "DESCRIPTION");
            for (var entry : tools.entrySet()) {
                out.printf(fmt, cell(entry.getKey()),
                        cell(sourceOf.apply(entry.getKey())), cell(entry.getValue().description()));
            }
        }

        /** The fields of {@code isx tools list --format=plain|json}, in order: add to the end, never rename. */
        static List<Map<String, Object>> records(Map<String, ToolSetup> tools,
                                                 UnaryOperator<String> sourceOf) {
            var records = new ArrayList<Map<String, Object>>();
            tools.forEach((name, tool) -> {
                var record = new LinkedHashMap<String, Object>();
                record.put("name", name);
                record.put("source", sourceOf.apply(name));
                record.put("description", tool.description());
                records.add(record);
            });
            return records;
        }
    }

    // ── show ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "show", description = "Show details of a tool definition",
            generateHelp = true)
    public static class Show extends BaseCommand {

        @Argument(required = true, description = "Tool name")
        String name;

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", description = "Output format: table (default), plain or json")
        String format;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            var loader = RuntimeServices.toolDefLoader();
            var tools = loader.allToolSetups();
            var tool = tools.get(name);
            if (tool == null) {
                System.err.println("Tool '" + name + "' not found.");
                line(System.err, "Available tools: " + String.join(", ",
                        new TreeMap<>(tools).keySet()));
                return CommandResult.valueOf(1);
            }
            if (outputFormat != OutputFormat.TABLE) {
                outputFormat.printOne(System.out, record(tool, loader.getSource(name)));
                return CommandResult.SUCCESS;
            }

            print(System.out, tool, loader.getSource(name));
            return CommandResult.SUCCESS;
        }

        /**
         * The fields of {@code isx tools show --format=plain|json}, in order: add to the end,
         * never rename. Lists name what the table details; {@code proxy_domains} are the
         * domains whose credentials the proxy injects for the tool.
         */
        static Map<String, Object> record(ToolSetup tool, String source) {
            var record = new LinkedHashMap<String, Object>();
            record.put("name", tool.name());
            record.put("description", tool.description());
            record.put("source", source);
            record.put("feature", tool.feature());
            record.put("requires", List.copyOf(tool.requires()));
            record.put("packages", List.copyOf(tool.packages()));
            record.put("parameters", List.copyOf(tool.parameters().keySet()));
            record.put("actions", tool.actions().stream().map(a -> a.getLabel()).toList());
            record.put("downloads", tool instanceof YamlToolSetup yaml
                    ? yaml.toolDef().getDownloads().stream().map(d -> d.getUrl()).toList() : List.of());
            var domains = new ArrayList<String>();
            var proxy = tool.proxy();
            if (proxy != null && proxy.getAuth() != null) {
                for (var auth : proxy.getAuth()) {
                    if (auth.getDomains() != null) domains.addAll(auth.getDomains());
                }
            }
            record.put("proxy_domains", domains);
            return record;
        }

        /** The {@code table} format: the tool's details, one per line. */
        static void print(PrintStream out, ToolSetup tool, String source) {
            line(out, tool.name());
            line(out, "  Description:  " + tool.description());
            line(out, "  Source:        " + source);
            if (tool.feature() != null) {
                line(out, "  Feature gate:  " + tool.feature());
            }

            printRequires(out, tool);
            printPackages(out, tool);
            printParameters(out, tool);
            printActions(out, tool);
            printDownloads(out, tool);
            printProxy(out, tool);
        }

        private static void printRequires(PrintStream out, ToolSetup tool) {
            var requires = tool.requires();
            if (!requires.isEmpty()) {
                line(out, "  Requires:      " + String.join(", ", requires));
            }
        }

        private static void printPackages(PrintStream out, ToolSetup tool) {
            var packages = tool.packages();
            if (!packages.isEmpty()) {
                line(out, "  Packages:      " + String.join(", ", packages));
            }
        }

        private static void printParameters(PrintStream out, ToolSetup tool) {
            var params = tool.parameters();
            if (params.isEmpty()) return;
            line(out, "  Parameters:");
            for (var entry : params.entrySet()) {
                var p = entry.getValue();
                var sb = new StringBuilder("    ").append(entry.getKey());
                if (p.getType() != null) sb.append(" (").append(p.getType()).append(')');
                if (p.getDefault() != null) sb.append(" default=").append(p.getDefault());
                line(out, sb);
                if (p.getDescription() != null && !p.getDescription().isBlank()) {
                    line(out, "      " + p.getDescription());
                }
                if (p.getOptions() != null && !p.getOptions().isEmpty()) {
                    line(out, "      options: " + String.join(", ", p.getOptions()));
                }
            }
        }

        private static void printActions(PrintStream out, ToolSetup tool) {
            var actions = tool.actions();
            if (actions.isEmpty()) return;
            line(out, "  Actions:");
            for (var a : actions) {
                var sb = new StringBuilder("    ").append(a.getLabel());
                if (a.getType() != null) sb.append(" (").append(a.getType()).append(')');
                line(out, sb);
            }
        }

        private static void printDownloads(PrintStream out, ToolSetup tool) {
            if (!(tool instanceof YamlToolSetup yaml)) return;
            var downloads = yaml.toolDef().getDownloads();
            if (downloads.isEmpty()) return;
            line(out, "  Downloads:");
            for (var dl : downloads) {
                var sb = new StringBuilder("    ").append(dl.getUrl());
                if (dl.getArch() != null) sb.append(" [").append(dl.getArch()).append(']');
                line(out, sb);
            }
        }

        private static void printProxy(PrintStream out, ToolSetup tool) {
            var proxy = tool.proxy();
            if (proxy == null) return;
            var auth = proxy.getAuth();
            if (auth == null || auth.isEmpty()) return;
            line(out, "  Proxy domains:");
            for (var a : auth) {
                if (a.getDomains() != null) {
                    for (var domain : a.getDomains()) {
                        line(out, "    " + domain + " (" + a.getType() + ")");
                    }
                }
            }
        }
    }

    // Definition text is untrusted (a project-local tool ships with a cloned repository), so a
    // table shows it as --format=plain does: nothing in it can drive the terminal (#1133).
    private static String cell(Object value) {
        return OutputFormat.oneLine(String.valueOf(value));
    }

    private static void line(PrintStream out, Object text) {
        out.println(cell(text));
    }
}
