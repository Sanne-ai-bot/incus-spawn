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

    @Test
    void theAccountsErrorAndTemplateDetailsWrapByCellsToo() {
        var wrapped = List.of("日本語 日本語", "日本語");
        assertEquals(wrapped, AccountsModal.wrap("日本語 日本語 日本語", 14));
        assertEquals(wrapped, TemplateDetailView.wrap("日本語 日本語 日本語", 14));
        assertEquals(List.of("jdk     25"), TemplateDetailView.wrap("jdk     25", 14));
        assertEquals(List.of("a b c"), TemplateDetailView.wrap("a b c", 0));
    }
}
