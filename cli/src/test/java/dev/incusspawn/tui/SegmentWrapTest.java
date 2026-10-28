package dev.incusspawn.tui;

import dev.incusspawn.util.TerminalLink;
import dev.incusspawn.util.TerminalLink.Segment;
import dev.tamboui.text.CharWidth;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AI Help wraps an answer by terminal cells and grapheme clusters, never by UTF-16 units (#1412). */
class SegmentWrapTest {

    @Test
    void widthCountsCellsNotChars() {
        assertEquals(18, SegmentWrap.width(TerminalLink.parseSegments(
                "[日本語ドキュメント](https://x)")));
        assertEquals(13, SegmentWrap.width(TerminalLink.parseSegments("no links here")));
        assertEquals(2, SegmentWrap.width(TerminalLink.parseSegments("😀")));
    }

    @Test
    void wideTextWrapsAtTheLinesWidthInCells() {
        var text = "日本語の文章です";
        var lines = render(SegmentWrap.wrap(List.of(new Segment.Text(text)), 10));
        assertEquals(List.of("日本語の文", "章です"), lines);
    }

    @Test
    void anEmojiAtTheBreakIsNeverSplit() {
        var text = "abcd😀efgh";
        var lines = render(SegmentWrap.wrap(List.of(new Segment.Text(text)), 5));
        assertEquals(List.of("abcd", "😀efg", "h"), lines);
    }

    @Test
    void aZwjSequenceStaysOnOneLine() {
        var family = "👨‍👩‍👧";
        var lines = render(SegmentWrap.wrap(List.of(new Segment.Text("a" + family + "b")), 2));
        assertEquals(List.of("a", family, "b"), lines);
    }

    @Test
    void aWideLinkMovesToTheNextLineByItsCells() {
        var segments = List.<Segment>of(
                new Segment.Text("see "),
                new Segment.Link("https://x", "日本語"));
        // "see " is 4 cells and the label 6: 10 fits in 10, not in 9.
        assertEquals(1, SegmentWrap.wrap(segments, 10).size());
        var wrapped = SegmentWrap.wrap(segments, 9);
        assertEquals(2, wrapped.size());
        assertInstanceOf(Segment.Link.class, wrapped.get(1).getFirst());
    }

    @Test
    void textAfterALinkWiderThanTheLineStartsANewLine() {
        var segments = List.<Segment>of(
                new Segment.Link("https://example.com/a/long/path", "https://example.com/a/long/path"),
                new Segment.Text(" and then some words"));
        var lines = render(SegmentWrap.wrap(segments, 10));
        assertEquals("https://example.com/a/long/path", lines.getFirst());
        for (var line : lines.subList(1, lines.size())) assertTrue(CharWidth.of(line) <= 10, line);
        assertEquals("and then some words", String.join(" ", lines.subList(1, lines.size())));
    }

    @Test
    void breaksAtTheLastSpaceThatFits() {
        var segments = List.<Segment>of(
                new Segment.Text("see "),
                new Segment.Link("https://example.com", "docs"),
                new Segment.Text(" for info"));
        assertEquals(1, SegmentWrap.wrap(segments, 20).size());
        assertEquals(List.of("see docs for", "info"), render(SegmentWrap.wrap(segments, 12)));
    }

    @Test
    void everyLineFitsAndNoSurrogateIsLeftAlone() {
        var text = "日本 😀😀😀 語語語語語 café 🇫🇷🇫🇷";
        for (int width = 2; width <= 12; width++) {
            var lines = render(SegmentWrap.wrap(TerminalLink.parseSegments(text), width));
            for (var line : lines) {
                assertTrue(CharWidth.of(line) <= width, width + ": " + line);
                assertTrue(line.isEmpty() || !Character.isLowSurrogate(line.charAt(0)), width + ": " + line);
                assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)), width + ": " + line);
            }
            assertEquals(text.replace(" ", ""), String.join("", lines).replace(" ", ""));
        }
    }

    private static List<String> render(List<List<Segment>> lines) {
        return lines.stream()
                .map(line -> String.join("", line.stream().map(SegmentWrap::shown).toList()))
                .toList();
    }
}
