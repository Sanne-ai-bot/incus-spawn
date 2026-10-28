package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Output reaches the terminal only through the gate classes below (#1703).
 * A message printed straight to {@code System.out} or {@code System.err} from code the TUI can
 * reach is drawn over and lost (#872, #1154, #1646, #1689), and a line indented by hand drifts
 * from the group it sits in, so main source of all three modules may not use or replace the raw
 * streams, use the console or {@code printStackTrace()}, nor print a string literal that starts
 * with spaces, through a stream or the proxy's {@code ProxyLog.stdout} alike.
 *
 * <p>The code that predates the gate is held by {@value #BASELINE}: per file, how many of each
 * it still has. A file not listed may have none, a listed one may not grow, and a listed count
 * above what the file holds fails too, so the baseline only ever shrinks and every migration
 * deletes its lines. Comments and the contents of string literals are not scanned.
 */
class RawOutputGateTest {

    private static final String BASELINE = "raw-output-baseline.txt";

    private static final Pattern RAW_STREAM = Pattern.compile(
            "\\bSystem\\s*\\.\\s*(?:(?:out|err)\\b|(?:console|setOut|setErr)\\s*\\()"
                    + "|\\bSystem\\s*::\\s*(?:console|setOut|setErr)\\b"
                    + "|\\bFileDescriptor\\s*\\.\\s*(?:out|err)\\b"
                    + "|\\.\\s*printStackTrace\\s*\\(\\s*\\)");
    private static final Pattern INDENTED_PRINT = Pattern.compile(
            "\\b(?:print|println|printf|stdout)\\s*\\(\\s*\"(?:\\\\n|%n)*(?: |\\\\t)");
    /** The pieces of a string literal's start that {@link #blank} keeps, so {@link #INDENTED_PRINT} can see them. */
    private static final Set<String> LEADING_INDENT = Set.of(" ", "\\n", "\\t", "%", "n");
    /** Never needed, so never baselined: it would hide every bare {@code out.println} that follows it. */
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "\\bimport\\s+static\\s+java\\.lang\\.System\\.(?:out|err|console|setOut|setErr|\\*)\\s*;");

    /** Repository-relative paths of the gate classes, each with why it may write. */
    private static final Map<String, String> EXEMPT = Map.of(
            "common/src/main/java/dev/incusspawn/util/TerminalOutput.java", "the terminal printer",
            "common/src/main/java/dev/incusspawn/util/TerminalStreams.java", "the process's streams, for what hands the terminal over or draws on it whole",
            "cli/src/main/java/dev/incusspawn/command/StdoutOnStderr.java", "points stdout at stderr while a machine format gets its answer",
            "cli/src/main/java/dev/incusspawn/mcp/StdioGuard.java", "moves stdout off the MCP protocol stream",
            "cli/src/main/java/dev/incusspawn/command/Prompts.java", "owns the console",
            "common/src/main/java/dev/incusspawn/proxy/ProxyLog.java", "the proxy's log, silent while the TUI draws",
            "cli/src/main/java/dev/incusspawn/mcp/McpLog.java", "isx mcp's log on stderr, its client's log, beside the protocol on stdout",
            "cli/src/main/java/dev/incusspawn/tui/StreamWatch.java", "notes what anything prints before the TUI opens, so the hand-over waits for it to be read");

    /** {@code graal/*Feature} classes run inside native-image at build time, so they print to its log. */
    private static final Pattern BUILD_TIME_FEATURE =
            Pattern.compile("common/src/main/java/dev/incusspawn/graal/[^/]*Feature\\.java");

    private static boolean exempt(String path) {
        return EXEMPT.containsKey(path) || BUILD_TIME_FEATURE.matcher(path).matches();
    }

    record Count(int raw, int indented) {
        static final Count NONE = new Count(0, 0);
    }

    /** What a file still holds: the one-based lines of each kind of offence. */
    record Found(List<Integer> raw, List<Integer> indented) {
        Count count() {
            return new Count(raw.size(), indented.size());
        }
    }

    @Test
    void outputStaysBehindTheGate() throws IOException {
        var baseline = readBaseline();
        var failures = new StringBuilder();
        var scanned = scan(failures);
        for (var entry : scanned.entrySet()) {
            var path = entry.getKey();
            var found = entry.getValue();
            var allowed = baseline.getOrDefault(path, Count.NONE);
            over(failures, path, "raw output uses", found.raw(), allowed.raw());
            over(failures, path, "indented print literals", found.indented(), allowed.indented());
        }
        assertEquals("", failures.toString(),
                "write through BuildOutput or a sink the caller hands in (CLAUDE.md, \"Never print straight to"
                        + " stdout/stderr\"), and indent with BuildOutput's groups, not leading spaces");

        var stale = new StringBuilder();
        for (var entry : baseline.entrySet()) {
            var found = scanned.get(entry.getKey());
            var actual = found == null ? Count.NONE : found.count();
            if (actual.equals(Count.NONE)) {
                stale.append("delete: ").append(line(entry.getKey(), entry.getValue())).append('\n');
            } else if (!actual.equals(entry.getValue())) {
                stale.append("lower to: ").append(line(entry.getKey(), actual)).append('\n');
            }
        }
        assertEquals("", stale.toString(), "the baseline only shrinks: update cli/src/test/resources/" + BASELINE);
    }

    private static void over(StringBuilder failures, String path, String kind, List<Integer> lines, int allowed) {
        if (lines.size() <= allowed) return;
        failures.append(path).append(": ").append(lines.size()).append(' ').append(kind).append(", ")
                .append(allowed).append(" allowed, at lines ").append(lines).append('\n');
    }

    private static final Pattern TERMINAL_STREAMS = Pattern.compile("\\bTerminalStreams\\s*(?:\\.|::)");

    /**
     * Who may take the terminal's own streams from {@code TerminalStreams}, each with why: the gate
     * is exempt, so without this list any code could print raw through it.
     */
    private static final Map<String, String> MAY_USE_TERMINAL_STREAMS = Map.of(
            "common/src/main/java/dev/incusspawn/incus/IncusClient.java", "hands the terminal to an interactive guest command",
            "common/src/main/java/dev/incusspawn/incus/IncusApi.java", "writes an interactive shell's raw bytes",
            "common/src/main/java/dev/incusspawn/incus/InteractiveShell.java", "draws the shell's status bar on the terminal it hands the guest",
            "common/src/main/java/dev/incusspawn/util/TerminalOutput.java", "writes a window title only where TerminalStreams.terminal() finds one",
            "common/src/main/java/dev/incusspawn/util/TerminalProgress.java", "the live renderer, which draws on the terminal whole",
            "common/src/main/java/dev/incusspawn/git/HostRepoRefresh.java", "asks on the console, where Prompts cannot reach",
            "cli/src/main/java/dev/incusspawn/command/Prompts.java", "its terminal() asks only when stdin and stdout are a terminal",
            "cli/src/main/java/dev/incusspawn/command/GitRemoteHelperCommand.java", "a git remote helper: stdin and stdout are git's protocol, stderr what git shows",
            "cli/src/main/java/dev/incusspawn/command/SshProxyCommand.java", "an ssh ProxyCommand: stdin and stdout are the connection, stderr what ssh shows");

    @Test
    void onlyTheNamedPassthroughsTakeTheTerminalsStreams() throws IOException {
        var stray = new StringBuilder();
        for (var source : sources("src/main/java").entrySet()) {
            var path = source.getKey();
            if (path.endsWith("/util/TerminalStreams.java") || MAY_USE_TERMINAL_STREAMS.containsKey(path)) continue;
            var uses = lines(TERMINAL_STREAMS, blank(source.getValue()));
            if (!uses.isEmpty()) stray.append(path).append(" at lines ").append(uses).append('\n');
        }
        assertEquals("", stray.toString(), "report through BuildOutput or a caller's Output; TerminalStreams is for"
                + " what hands the terminal over or draws on it whole (MAY_USE_TERMINAL_STREAMS)");
    }

    @Test
    void everyTerminalStreamsCallerStillCallsIt() throws IOException {
        assertEquals("", unneeded(MAY_USE_TERMINAL_STREAMS, TERMINAL_STREAMS),
                "drop these from MAY_USE_TERMINAL_STREAMS: they no longer call TerminalStreams");
    }

    @Test
    void findsTerminalStreamsCallsAndMethodReferences() {
        var source = """
                class A {
                    // TerminalStreams.out().println("comment");
                    String s = "TerminalStreams.err()";
                    var o = TerminalStreams.out();
                    Supplier<PrintStream> e = TerminalStreams::err;
                    Supplier<PrintStream> f = dev.incusspawn.util.TerminalStreams :: out;
                    var c = TerminalStreams . terminal();
                }
                import static dev.incusspawn.util.TerminalStreams.out;
                import dev.incusspawn.util.TerminalStreams;
                """;
        assertEquals(List.of(4, 5, 6, 7, 9), lines(TERMINAL_STREAMS, blank(source)));
    }

    @Test
    void everyExemptionIsStillNeeded() throws IOException {
        assertEquals("", unneeded(EXEMPT, RAW_STREAM),
                "drop these from EXEMPT: they write no raw output, and the gate should say so");
    }

    /** The paths in {@code listed} that are gone or no longer match {@code pattern}, one per line. */
    private static String unneeded(Map<String, String> listed, Pattern pattern) throws IOException {
        var repo = repo();
        var unneeded = new StringBuilder();
        for (var path : new TreeMap<>(listed).keySet()) {
            var file = repo.resolve(path);
            if (!Files.exists(file) || lines(pattern, blank(Files.readString(file))).isEmpty()) {
                unneeded.append(path).append('\n');
            }
        }
        return unneeded.toString();
    }

    private static Path repo() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    /**
     * Every non-exempt main source file of the three modules that holds an offence, by repository
     * path; a static import of a stream, in any file, goes straight to {@code failures}.
     */
    private static Map<String, Found> scan(StringBuilder failures) throws IOException {
        var scanned = new TreeMap<String, Found>();
        for (var source : sources("src/main/java").entrySet()) {
            var path = source.getKey();
            var code = blank(source.getValue());
            var imports = lines(STATIC_IMPORT, code);
            if (!imports.isEmpty()) {
                failures.append(path).append(": static import of a System stream at lines ")
                        .append(imports).append('\n');
            }
            if (exempt(path)) continue;
            var found = find(code);
            if (!found.count().equals(Count.NONE)) scanned.put(path, found);
        }
        return scanned;
    }

    /**
     * Every Java source under {@code dir} ({@code src/main/java}, {@code src/test/java}) of the
     * three modules, by repository path: the one walk every source gate makes, so a module added
     * is seen by all of them.
     */
    static Map<String, String> sources(String dir) throws IOException {
        var repo = repo();
        var sources = new TreeMap<String, String>();
        for (var module : List.of("cli", "common", "proxy")) {
            try (Stream<Path> files = Files.walk(repo.resolve(module).resolve(dir))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    sources.put(repo.relativize(file).toString().replace('\\', '/'), Files.readString(file));
                }
            }
        }
        return sources;
    }

    /** {@code code} is already {@link #blank blanked}. */
    private static Found find(String code) {
        return new Found(lines(RAW_STREAM, code), lines(INDENTED_PRINT, code));
    }

    @Test
    void findsUsesInCodeOnly() {
        var source = """
                class A {
                    // System.out.println("comment");
                    /* System.err */ String s = "System.out.println(x)";
                    String t = \"""
                        System.err.println("in a text block");
                        \""";
                    char q = '"'; char b = '\\''; String e = "\\" System.out \\\\";
                    void f(Exception e) {
                        System.out.println("  indented");
                        java.lang.System . err.print("x");
                        var c = System.console(); System.setErr(c); System . setOut (c); System.setIn(c);
                        e.printStackTrace();
                        e.printStackTrace(writer);
                        out.printf( " %s", s);
                        System.out.println("\\n  after a newline");
                        System.out.printf("%n\\tafter a tab%n");
                        System.out.println("no indent  "); Supplier<?> s = System::console;
                        BuildOutput.note("  not a print");
                    }
                }
                import static java.lang.System.out;
                import static java.lang.System.setErr;
                import static java.lang.System.setIn;
                """;
        assertEquals(new Found(List.of(9, 10, 11, 11, 11, 12, 15, 16, 17, 17, 21), List.of(9, 14, 15, 16)), find(blank(source)));
        assertEquals(List.of(21, 22), lines(STATIC_IMPORT, blank(source)));
    }

    private static String line(String path, Count count) {
        return count.raw() + " " + count.indented() + " " + path;
    }

    private static Map<String, Count> readBaseline() throws IOException {
        var baseline = new TreeMap<String, Count>();
        try (var in = RawOutputGateTest.class.getResourceAsStream("/" + BASELINE)) {
            assertNotNull(in, BASELINE + " is not on the test classpath");
            for (var line : new String(in.readAllBytes()).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                var fields = line.trim().split("\\s+");
                var previous = baseline.put(fields[2],
                        new Count(Integer.parseInt(fields[0]), Integer.parseInt(fields[1])));
                assertNull(previous, BASELINE + " lists " + fields[2] + " twice");
            }
        }
        return baseline;
    }

    /** One-based line numbers of each match. */
    private static List<Integer> lines(Pattern pattern, String code) {
        var found = new ArrayList<Integer>();
        var matcher = pattern.matcher(code);
        int line = 1;
        int counted = 0;
        while (matcher.find()) {
            for (; counted < matcher.start(); counted++) {
                if (code.charAt(counted) == '\n') line++;
            }
            found.add(line);
        }
        return found;
    }

    /**
     * The source with every comment, text block and character literal blanked to spaces, and
     * every string literal's contents to underscores except its leading run of spaces,
     * {@code \n}, {@code \t} and {@code %n}: enough to see an indentation, never enough to spell
     * a stream.
     * Quotes and newlines stay, so offsets still map to the same lines.
     */
    static String blank(String source) {
        var out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            int end;
            if (source.startsWith("//", i)) {
                end = source.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (source.startsWith("/*", i)) {
                end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                end = source.indexOf("\"\"\"", i + 3);
                while (end > 0 && escaped(source, end)) end = source.indexOf("\"\"\"", end + 1);
                end = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                boolean leading = c == '"';
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                    int next = source.charAt(i) == '\\' ? Math.min(i + 2, n) : i + 1;
                    leading &= LEADING_INDENT.contains(source.substring(i, next));
                    for (; i < next; i++) out.append(leading ? source.charAt(i) : '_');
                }
                if (i < n) out.append(source.charAt(i++));
                continue;
            } else {
                out.append(c);
                i++;
                continue;
            }
            for (; i < end; i++) out.append(source.charAt(i) == '\n' ? '\n' : ' ');
        }
        return out.toString();
    }

    private static boolean escaped(String source, int at) {
        int backslashes = 0;
        for (int k = at - 1; k >= 0 && source.charAt(k) == '\\'; k--) backslashes++;
        return backslashes % 2 == 1;
    }
}
