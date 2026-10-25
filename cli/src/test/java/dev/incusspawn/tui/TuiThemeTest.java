package dev.incusspawn.tui;

import dev.tamboui.style.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TuiThemeTest {

    @Test
    void darkThemeMatchesOriginalHardcodedColors() {
        var dark = TuiTheme.dark();

        // Panel chrome
        assertSame(Color.CYAN, dark.panelBorderFocused());
        assertSame(Color.DARK_GRAY, dark.panelBorderUnfocused());
        assertSame(Color.DARK_GRAY, dark.highlightBg());
        assertSame(Color.WHITE, dark.highlightFg());
        assertEquals(Color.indexed(239), dark.scrollbarTrack());

        // Text
        assertSame(Color.GRAY, dark.textDim());

        // Context / status bar
        assertEquals(Color.indexed(235), dark.contextBg());
        assertSame(Color.WHITE, dark.contextPrimaryFg());
        assertSame(Color.GRAY, dark.contextSecondaryFg());
        assertSame(Color.LIGHT_CYAN, dark.contextAccentFg());
        assertEquals(Color.indexed(17), dark.statusBarBg());
        assertSame(Color.LIGHT_RED, dark.statusBarErrorFg());

        // Modal
        assertEquals(Color.indexed(238), dark.modalBg());
        assertEquals(Color.indexed(189), dark.modalFg());
        assertSame(Color.CYAN, dark.modalBorder());
        assertSame(Color.LIGHT_CYAN, dark.modalAccent());
        assertSame(Color.LIGHT_RED, dark.modalWarn());
        assertEquals(Color.indexed(240), dark.inputBg());
        assertEquals(Color.indexed(236), dark.inputInactiveBg());
        assertEquals(Color.indexed(60), dark.placeholderFg());
        assertEquals(Color.indexed(232), dark.modalShadow());

        // Function key bar
        assertSame(Color.CYAN, dark.barBg());
        assertSame(Color.WHITE, dark.barKeyFg());
        assertSame(Color.BLACK, dark.barLabelFg());
        assertEquals(Color.indexed(23), dark.barDisabledFg());
        assertEquals(Color.indexed(30), dark.barSeparatorFg());

        // Semantic
        assertSame(Color.GREEN, dark.statusRunning());
        assertSame(Color.GRAY, dark.statusStopped());
        assertSame(Color.YELLOW, dark.statusWarning());
        assertSame(Color.GREEN, dark.statusSuccess());
        assertSame(Color.RED, dark.statusFailure());
        assertSame(Color.GREEN, dark.checkEnabled());
        assertSame(Color.GRAY, dark.checkDisabled());
        assertSame(Color.WHITE, dark.focusedLabel());
    }

    /** Termius draws every RGB foreground in one colour (#1681). */
    @Test
    void themesUseNoRgbColours() throws Exception {
        for (var theme : List.of(TuiTheme.dark(), TuiTheme.light())) {
            for (var component : TuiTheme.class.getRecordComponents()) {
                var colour = component.getAccessor().invoke(theme);
                assertFalse(colour instanceof Color.Rgb, component.getName() + " is " + colour);
            }
        }
    }

    @Test
    void lightThemeHasDifferentPalette() {
        var light = TuiTheme.light();
        var dark = TuiTheme.dark();

        assertNotEquals(dark.modalBg(), light.modalBg());
        assertNotEquals(dark.modalFg(), light.modalFg());
        assertNotEquals(dark.contextBg(), light.contextBg());
        assertNotEquals(dark.highlightBg(), light.highlightBg());
        assertNotEquals(dark.inputBg(), light.inputBg());
    }

    @Test
    void detectReturnsNonNull() {
        assertNotNull(TerminalThemeDetector.detect());
    }

    @Test
    void isDarkDefaultsWithoutTerminal() {
        // In a test environment (no real terminal), should not throw
        // and should return a definite answer (defaults to dark)
        assertTrue(TerminalThemeDetector.isDark());
    }
}
