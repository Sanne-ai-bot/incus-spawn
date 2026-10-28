package dev.incusspawn.tui;

import dev.incusspawn.util.TerminalLink.Segment;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.GraphemeClusters;

import java.util.ArrayList;
import java.util.List;

/**
 * Word-wraps {@link Segment}s, such as an AI Help answer, by the terminal cells they take (#1412):
 * a CJK ideograph or an emoji takes two, and a break never falls inside a grapheme cluster.
 */
final class SegmentWrap {

    private SegmentWrap() {}

    /** The cells {@code segments} take, links counted by their label. */
    static int width(List<Segment> segments) {
        int width = 0;
        for (var seg : segments) width += CharWidth.of(shown(seg));
        return width;
    }

    /** What {@code segment} shows: its text, or a link's label. */
    static String shown(Segment segment) {
        return switch (segment) {
            case Segment.Text t -> t.text();
            case Segment.Link l -> l.label();
        };
    }

    /** Wrap {@code segments} at {@code width} cells, a link being one unbreakable token. */
    static List<List<Segment>> wrap(List<Segment> segments, int width) {
        var result = new ArrayList<List<Segment>>();
        var currentLine = new ArrayList<Segment>();
        int col = 0;

        for (var seg : segments) {
            switch (seg) {
                case Segment.Link l -> {
                    int linkWidth = CharWidth.of(l.label());
                    if (col + linkWidth > width && col > 0) {
                        result.add(currentLine);
                        currentLine = new ArrayList<>();
                        col = 0;
                    }
                    currentLine.add(l);
                    col += linkWidth;
                }
                case Segment.Text t -> {
                    var text = t.text();
                    int pos = 0;
                    while (pos < text.length()) {
                        int fit = fitEnd(text, pos, width - col);
                        if (fit == text.length()) {
                            var rest = text.substring(pos);
                            currentLine.add(new Segment.Text(rest));
                            col += CharWidth.of(rest);
                            break;
                        }
                        int breakAt = text.lastIndexOf(' ', fit);
                        if (breakAt <= pos) breakAt = fit;
                        // A cluster wider than a whole line goes on one of its own.
                        if (breakAt == pos && col == 0) breakAt = GraphemeClusters.clusterEnd(text, pos);
                        if (breakAt > pos) currentLine.add(new Segment.Text(text.substring(pos, breakAt)));
                        result.add(currentLine);
                        currentLine = new ArrayList<>();
                        col = 0;
                        pos = breakAt;
                        if (pos < text.length() && text.charAt(pos) == ' ') pos++;
                    }
                }
            }
        }
        if (!currentLine.isEmpty()) {
            result.add(currentLine);
        }
        return result;
    }

    /** Where the whole clusters from {@code pos} that fit in {@code room} cells end. */
    private static int fitEnd(String text, int pos, int room) {
        int width = 0;
        while (pos < text.length()) {
            int end = GraphemeClusters.clusterEnd(text, pos);
            width += CharWidth.of(text.substring(pos, end));
            if (width > room) break;
            pos = end;
        }
        return pos;
    }
}
