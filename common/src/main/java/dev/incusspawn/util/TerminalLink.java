package dev.incusspawn.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Surface-agnostic link detection and OSC 8 terminal hyperlink generation.
 */
public final class TerminalLink {

    private TerminalLink() {}

    private static final String OSC8_OPEN = "\033]8;;";
    private static final String ST = "\033\\";
    private static final String OSC8_CLOSE = "\033]8;;" + ST;

    // Markdown link [text](url), or bare https?:// URL.
    // Markdown link group: (1)=label, (2)=url.  Bare URL group: (3)=url.
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "\\[([^\\]]+)]\\((https?://[^)]+)\\)"
                    + "|(https?://\\S+)");

    public sealed interface Segment {
        record Text(String text) implements Segment {}
        record Link(String url, String label) implements Segment {}
    }

    /** Wrap a URL as an OSC 8 clickable hyperlink (URL shown as display text). */
    public static String link(String url) {
        return link(url, url);
    }

    /**
     * Wrap a URL as an OSC 8 clickable hyperlink with custom display text. Off an ANSI terminal
     * (the same gate as colour, {@link BuildOutput#styled}) the URL is printed as text instead:
     * alone, or after a label that differs from it.
     */
    public static String link(String url, String label) {
        url = sanitize(url);
        label = sanitize(label);
        if (!BuildOutput.ansi()) return label.equals(url) ? url : label + " (" + url + ")";
        return OSC8_OPEN + url + ST + label + OSC8_CLOSE;
    }

    /**
     * Parse text into {@link Segment}s, detecting markdown links {@code [text](url)}
     * and bare {@code https://} URLs. Every segment is sanitized: text through {@link #plainText}
     * (#1413), a link's URL and label by dropping every {@link OutputFormat#isControl} character.
     */
    public static List<Segment> parseSegments(String text) {
        var segments = new ArrayList<Segment>();
        var matcher = LINK_PATTERN.matcher(text);
        int pos = 0;
        while (matcher.find()) {
            if (matcher.start() > pos) {
                segments.add(new Segment.Text(plainText(text.substring(pos, matcher.start()))));
            }
            if (matcher.group(1) != null) {
                segments.add(new Segment.Link(
                        sanitize(matcher.group(2)), sanitize(matcher.group(1))));
                pos = matcher.end();
            } else {
                var rawUrl = matcher.group(3);
                var url = sanitize(stripTrailingPunctuation(rawUrl));
                segments.add(new Segment.Link(url, url));
                pos = matcher.start() + stripTrailingPunctuation(rawUrl).length();
            }
        }
        if (pos < text.length()) {
            segments.add(new Segment.Text(plainText(text.substring(pos))));
        }
        return segments;
    }

    /**
     * Replace all links in {@code text} with OSC 8 clickable hyperlinks.
     * For markdown links, the display text is the link label; for bare URLs
     * the URL itself is shown.
     */
    public static String linkify(String text) {
        var segments = parseSegments(text);
        if (segments.size() == 1 && segments.getFirst() instanceof Segment.Text t) {
            return t.text();
        }
        var sb = new StringBuilder();
        for (var seg : segments) {
            switch (seg) {
                case Segment.Text t -> sb.append(t.text());
                case Segment.Link l -> sb.append(link(l.url(), l.label()));
            }
        }
        return sb.toString();
    }

    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;!?".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return end > 0 ? url.substring(0, end) : url;
    }

    /**
     * {@code text} with what a terminal may act on ({@link OutputFormat#isControl}) turned into
     * spaces, line feeds and tabs apart: how text someone else wrote, such as an AI answer, may
     * reach a terminal (#1413). A space, as {@link OutputFormat#oneLine} writes, keeps apart the
     * words a line separator or carriage return stood between.
     */
    public static String plainText(String text) {
        if (text.codePoints().noneMatch(TerminalLink::actsOnTerminal)) return text;
        var sb = new StringBuilder(text.length());
        text.codePoints().forEach(c -> sb.appendCodePoint(actsOnTerminal(c) ? ' ' : c));
        return sb.toString();
    }

    static boolean actsOnTerminal(int c) {
        return c != '\n' && c != '\t' && OutputFormat.isControl(c);
    }

    /** Drops what a terminal may act on ({@link OutputFormat#isControl}): C1 included, as U+009B is an 8-bit CSI. */
    private static String sanitize(String s) {
        if (s.codePoints().noneMatch(OutputFormat::isControl)) return s;
        var sb = new StringBuilder(s.length());
        s.codePoints().filter(c -> !OutputFormat.isControl(c)).forEach(sb::appendCodePoint);
        return sb.toString();
    }
}
