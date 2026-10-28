package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escapes reach the terminal only through the one gate, {@code BuildOutput.ansi()}: colour via
 * {@code BuildOutput.styled()}, hyperlinks via {@code TerminalLink.link()}, and neither off an
 * ANSI terminal or under {@code NO_COLOR} (#1037, #1082). Any other raw escape (an ESC literal in
 * any spelling, {@code \033}, {@code \33}, {@code \u001b}, a cast {@code (char) 27} of any 27
 * literal, or {@code Character.toString(27)} and {@code toChars(27)}, whatever follows it) must be on a path that only ever draws on a terminal (an
 * animated line, a window title behind {@code hasTerminal()}), and says so with a
 * {@value #MARKER} {@code //} comment on that same line, so a marker never covers a line it was
 * not written for, and one in a string, text block or block comment counts for nothing. {@code \\033} in a Java literal is text for a shell's {@code printf} inside the instance,
 * not an escape isx writes, and is not matched. A regular expression that matches escapes carries
 * the marker too ({@code // raw ANSI: matches escapes, writes none}). Unicode escapes are decoded
 * first, as the compiler does. An ESC computed at run time from a number, and a byte {@code 0x1B}
 * (what {@code ShellStatusBar} writes and {@code EscapeSequenceParser} reads), are beyond a
 * source scan of literals; review catches those. Only the files
 * below are exempt as a whole.
 */
class AnsiEscapeGateTest {

    /**
     * 27 as a number literal: decimal, hex, octal or binary, int or long, underscores anywhere
     * inside, after any parentheses and widening casts.
     */
    private static final String ESC_NUMBER = "(?:\\s|\\(\\s*(?:int|long|short|byte|char)\\s*\\)|\\()*"
            + "(?:2_*7|0[xX][0_]*1_*[bB]|0[0_]*3_*3|0[bB][0_]*1_*1_*0_*1_*1)[lL]?\\b";

    /** Matched in a line whose Unicode escapes are decoded, so {@code \\u001b} is a real ESC ({@code \\x1B}) by then. */
    private static final Pattern RAW_ESCAPE = Pattern.compile(
            "\\x1B|(?<!\\\\)(?:\\\\\\\\)*\\\\(?:033|33(?![0-7]))"
                    + "|\\(\\s*char\\s*\\)" + ESC_NUMBER
                    + "|\\bCharacter\\s*\\.\\s*to(?:String|Chars)\\s*\\(" + ESC_NUMBER);
    private static final String MARKER = "raw ANSI:";

    /** Repository-relative paths, so a namesake elsewhere is scanned. */
    private static final Map<String, String> EXEMPT = Map.of(
            "common/src/main/java/dev/incusspawn/util/BuildOutput.java", "the colour gate",
            "common/src/main/java/dev/incusspawn/util/TerminalOutput.java", "the terminal printer and its live step",
            "common/src/main/java/dev/incusspawn/util/TerminalLink.java", "the hyperlink gate",
            "common/src/main/java/dev/incusspawn/util/TerminalProgress.java", "animated lines and the terminal check itself",
            "common/src/main/java/dev/incusspawn/incus/ShellStatusBar.java", "drawn inside an interactive shell, which needs a terminal");

    @Test
    void rawEscapesStayBehindTheGate() throws IOException {
        var offenders = new StringBuilder();
        for (var source : RawOutputGateTest.sources("src/main/java").entrySet()) {
            if (EXEMPT.containsKey(source.getKey())) continue;
            offenders.append(offences(source.getKey(), source.getValue()));
        }
        assertEquals("", offenders.toString(),
                "style through BuildOutput.styled() or link through TerminalLink.link(); a path that"
                        + " only ever draws on a terminal says why with a '// " + MARKER + "' comment");
    }

    @Test
    void everyExemptionIsStillAFile() {
        for (var path : EXEMPT.keySet()) {
            assertTrue(Files.exists(RawOutputGateTest.repo().resolve(path)), path + " is gone: drop it from EXEMPT");
        }
    }

    @Test
    void findsEveryEscapeSpelling() {
        var source = """
                String a = "\\033[2J";
                String b = "\\33[2J";
                String c = "\\uu001b[31m";
                String d = "\\033" + "[0m";
                String e = "\\033c";
                String f = (char) 27 + "[2J"; char g = (char)0x1B;
                String h = Character.toString(27);
                String i = "\\033[2m"; // raw ANSI: animated line
                String j = "printf '\\\\033[0m'";
                Pattern k = Pattern.compile("\\033\\\\[[^m]*m"); // Pattern.compile( is no exemption
                String l = "\\331"; String m = "\\0331";
                String n = "\\\\\\033[2J"; String o = "\\u005c033[2J";
                String p = "\\033\\\\";
                String q = "raw ANSI: in a string" + "\\033[2J"; String r = "// raw ANSI: no comment\\033[2J";
                String s = \"""
                    \\033[2J // raw ANSI: in a text block
                    \""";
                char t = (char) (27); char u = (char) 27L; char[] v = Character.toChars(0x1b);
                char w = (char) 0b1_1011; char x = (char) ((int) 2_7); String y = Character.toString(0x1_B);
                foo(); // raw ANSI: x \\u000a String z = "\\u001b[2J";
                /* // raw ANSI: in a block comment */ String b = "\\033[2J";
                char d = (char) 270; int e = 27;
                /* \\u000a */ String f = "\\033[2J"; // raw ANSI: after a decoded newline in a block comment
                """;
        assertEquals("A.java:1\nA.java:2\nA.java:3\nA.java:4\nA.java:5\nA.java:6\nA.java:7\nA.java:10\nA.java:11\nA.java:12\nA.java:13\nA.java:14\nA.java:16\nA.java:18\nA.java:19\nA.java:20\nA.java:21\n",
                offences("A.java", source));
    }

    /**
     * Whether the line's {@code //} comment carries the {@value #MARKER} marker. {@code code} is the
     * same line of the whole file blanked, where a line comment alone keeps its {@code //}: one in a
     * string, a text block or a block comment is not one.
     */
    private static boolean marked(String line, String code) {
        int start = code.indexOf("//");
        return start >= 0 && line.indexOf(MARKER, start) >= 0;
    }

    /** The {@code path:line} of each unmarked raw escape in {@code source}, one per line. */
    private static String offences(String path, String source) {
        var offenders = new StringBuilder();
        // Blanked as a whole, so a line knows whether it sits in a text block; decoding keeps lines
        var whole = RawOutputGateTest.unicodeDecoded(source);
        var decoded = whole.split("\n", -1);
        var code = RawOutputGateTest.blankDecoded(whole, false).split("\n", -1);
        for (int i = 0; i < decoded.length; i++) {
            // A decoded line terminator (a \r) ends the compiler's line, and with it a comment's marker
            var parts = decoded[i].split("\r", -1);
            var codeParts = code[i].split("\r", -1);
            for (int p = 0; p < parts.length; p++) {
                if (RAW_ESCAPE.matcher(parts[p]).find() && !marked(parts[p], codeParts[p])) {
                    offenders.append(path).append(':').append(i + 1).append('\n');
                    break;
                }
            }
        }
        return offenders.toString();
    }
}
