package dev.incusspawn.tui;

import org.junit.jupiter.api.Test;

/** The dialogs ModalRenderer draws itself, for the TUI's error and progress states. */
class ModalRendererTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    @Test
    void rendersAnError() {
        var modal = new ModalRenderer(THEME);
        TuiSnapshot.assertMatches("error-modal-80x24", TuiSnapshot.render(80, 24,
                f -> modal.renderErrorModal(f, f.area(), "The MITM proxy is not running.\n\nStart it:\n  isx proxy start")));
    }

    @Test
    void rendersProgressOverTheScreen() {
        var modal = new ModalRenderer(THEME);
        TuiSnapshot.assertMatches("progress-overlay-80x24", TuiSnapshot.render(80, 24,
                f -> modal.renderProgressOverlay(f, f.area(), "Scanning pool...")));
    }
}
