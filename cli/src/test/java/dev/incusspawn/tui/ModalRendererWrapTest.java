package dev.incusspawn.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModalRendererWrapTest {

    @Test
    void wrapsAtWordBoundaries() {
        assertEquals(List.of("one two", "three"), ModalRenderer.wrapText("one two three", 8));
    }

    @Test
    void indentationIsKeptOnALineAndItsContinuations() {
        assertEquals(List.of("fix:", "  - name: X", "    value: a b", "    c"),
                ModalRenderer.wrapText("fix:\n  - name: X\n    value: a b c", 14));
    }

    @Test
    void blankLinesStay() {
        assertEquals(List.of("a", "", "b"), ModalRenderer.wrapText("a\n\nb", 10));
    }

    @Test
    void wideWordsWrapByTheCellsTheyTake() {
        // Each word is 3 ideographs, 6 cells: two and a space take 13 of 14 cells, three take 20.
        assertEquals(List.of("日本語 日本語", "日本語"), ModalRenderer.wrapText("日本語 日本語 日本語", 14));
    }
}
