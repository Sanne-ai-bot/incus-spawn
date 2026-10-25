package dev.incusspawn.tui;

import dev.tamboui.style.Color;

/** The TUI's colours: named ANSI colours and 256-colour palette entries, never RGB (#1681). */
public record TuiTheme(
        // Panel chrome
        Color panelBorderFocused,
        Color panelBorderUnfocused,
        Color highlightBg,
        Color highlightFg,
        Color scrollbarTrack,

        // Text
        Color textDim,

        // Context / status bar
        Color contextBg,
        Color contextPrimaryFg,
        Color contextSecondaryFg,
        Color contextAccentFg,
        Color statusBarBg,
        Color statusBarErrorFg,

        // Modal
        Color modalBg,
        Color modalFg,
        Color modalBorder,
        Color modalAccent,
        Color modalWarn,
        Color inputBg,
        Color inputInactiveBg,
        Color placeholderFg,
        Color modalShadow,

        // Function key bar
        Color barBg,
        Color barKeyFg,
        Color barLabelFg,
        Color barDisabledFg,
        Color barSeparatorFg,

        // Semantic
        Color statusRunning,
        Color statusStopped,
        Color statusWarning,
        Color statusSuccess,
        Color statusFailure,
        Color checkEnabled,
        Color checkDisabled,
        Color focusedLabel
) {

    public static TuiTheme dark() {
        return new TuiTheme(
                // Panel chrome
                Color.CYAN,
                Color.DARK_GRAY,
                Color.DARK_GRAY,
                Color.WHITE,
                Color.indexed(239),

                // Text
                Color.GRAY,

                // Context / status bar
                Color.indexed(235),
                Color.WHITE,
                Color.GRAY,
                Color.LIGHT_CYAN,
                Color.indexed(17),
                Color.LIGHT_RED,

                // Modal
                Color.indexed(238),
                Color.indexed(189),
                Color.CYAN,
                Color.LIGHT_CYAN,
                Color.LIGHT_RED,
                Color.indexed(240),
                Color.indexed(236),
                Color.indexed(60),
                Color.indexed(232),

                // Function key bar
                Color.CYAN,
                Color.WHITE,
                Color.BLACK,
                Color.indexed(23),
                Color.indexed(30),

                // Semantic
                Color.GREEN,
                Color.GRAY,
                Color.YELLOW,
                Color.GREEN,
                Color.RED,
                Color.GREEN,
                Color.GRAY,
                Color.WHITE
        );
    }

    public static TuiTheme light() {
        return new TuiTheme(
                // Panel chrome
                Color.BLUE,
                Color.GRAY,
                Color.indexed(146),
                Color.BLACK,
                Color.indexed(250),

                // Text
                Color.DARK_GRAY,

                // Context / status bar
                Color.indexed(253),
                Color.BLACK,
                Color.DARK_GRAY,
                Color.BLUE,
                Color.indexed(189),
                Color.RED,

                // Modal
                Color.indexed(255),
                Color.indexed(235),
                Color.BLUE,
                Color.BLUE,
                Color.RED,
                Color.indexed(254),
                Color.indexed(231),
                Color.indexed(247),
                Color.indexed(250),

                // Function key bar
                Color.BLUE,
                Color.WHITE,
                Color.WHITE,
                Color.indexed(104),
                Color.indexed(67),

                // Semantic
                Color.GREEN,
                Color.DARK_GRAY,
                Color.YELLOW,
                Color.GREEN,
                Color.RED,
                Color.GREEN,
                Color.DARK_GRAY,
                Color.BLACK
        );
    }
}
