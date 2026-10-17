package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyEvent;

import java.util.List;

/**
 * The confirmation before F7 or Shift+F7 stops or restarts the marked instances (#1482): it lists
 * every running one it acts on, none counted away, and names the marked ones it leaves alone
 * because they are not running. It owns its keys and its rendering; the {@link Tui} runs the stops.
 */
final class MarkedStopConfirm {

    /** What a key did: confirm, or close the dialog without acting. */
    enum Outcome { CLOSE, CONFIRM }

    private final ModalRenderer modal;
    private final List<String> names;
    private final List<String> notRunning;
    private final boolean restart;

    /**
     * @param names      the running marked instances, which a confirmation stops or restarts
     * @param notRunning the marked instances left alone
     * @param restart    Shift+F7: restart rather than stop
     */
    MarkedStopConfirm(ModalRenderer modal, List<String> names, List<String> notRunning, boolean restart) {
        this.modal = modal;
        this.names = List.copyOf(names);
        this.notRunning = List.copyOf(notRunning);
        this.restart = restart;
    }

    /** The instances a confirmation stops or restarts, in order. */
    List<String> names() {
        return names;
    }

    boolean restart() {
        return restart;
    }

    /** Any key closes the dialog; only y confirms. */
    Outcome handleKey(KeyEvent key) {
        return key.isChar('y') || key.isChar('Y') ? Outcome.CONFIRM : Outcome.CLOSE;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        var verb = restart ? "Restart" : "Stop";
        var title = " " + verb + " " + names.size() + (names.size() == 1 ? " instance " : " instances ");
        modal.renderConfirmModal(frame, screen, title, message(screen.height()), modal.warn(), verb);
    }

    /** Every name it acts on, none counted away (Sanne asked that all be listed), and those it leaves alone. */
    String message(int screenHeight) {
        var leftAlone = notRunning.isEmpty() ? "" : "Not running, left as is: " + String.join(", ", notRunning) + ".";
        return ModalRenderer.listMessage(restart ? "This will restart" : "This will stop", names, leftAlone, screenHeight);
    }
}
