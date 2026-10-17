package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyEvent;

import java.util.List;

/**
 * The TUI's delete confirmation (F8, F8 on the marked rows, or Shift+F8 for all templates or all
 * instances): what is about to be destroyed, with the CoW caveat note worked out once when it opened. It owns its
 * keys and its rendering; the {@link Tui} runs the destroy when it is confirmed.
 */
final class DeleteConfirm {

    /** What a key did: confirm the destroy, or close the dialog without it. */
    enum Outcome { CLOSE, CONFIRM }

    private final ModalRenderer modal;
    /** An instance or template name, or "--all" / "--all-instances". */
    private final String pendingDeleteName;
    private final String pendingDeleteNote;      // computed once when the confirm dialog opens
    /** The marked templates or instances to destroy, in order (#1482); empty for one name or "--all". */
    private final List<String> several;
    /** What {@link #several} are. */
    enum Kind {
        TEMPLATE("template"), INSTANCE("instance");

        final String noun;

        Kind(String noun) {
            this.noun = noun;
        }
    }

    private final Kind kind;

    DeleteConfirm(ModalRenderer modal, String name, String note) {
        this(modal, name, note, List.of(), null);
    }

    private DeleteConfirm(ModalRenderer modal, String name, String note, List<String> several, Kind kind) {
        this.modal = modal;
        this.pendingDeleteName = name;
        this.pendingDeleteNote = note;
        this.several = List.copyOf(several);
        this.kind = kind;
    }

    /** The confirmation to destroy the marked {@code names}, each a {@code kind}. */
    static DeleteConfirm ofSeveral(ModalRenderer modal, List<String> names, Kind kind) {
        return new DeleteConfirm(modal, null, "", names, kind);
    }

    /** What is to be destroyed: an instance or template name, or "--all" / "--all-instances"; null for several. */
    String name() {
        return pendingDeleteName;
    }

    /** The marked names to destroy, or empty when this confirms one name or "--all". */
    List<String> several() {
        return several;
    }

    /** What {@link #several} are; null for one name or "--all". */
    Kind kind() {
        return kind;
    }

    /** Any key closes the dialog; only y confirms. */
    Outcome handleKey(KeyEvent key) {
        if (key.isChar('y') || key.isChar('Y')) {
            return Outcome.CONFIRM;
        }
        return Outcome.CLOSE;
    }

    void render(dev.tamboui.terminal.Frame frame, dev.tamboui.layout.Rect screen) {
        if (!several.isEmpty()) {
            var title = " Destroy " + several.size() + " " + kind.noun + (several.size() == 1 ? " " : "s ");
            modal.renderConfirmModal(frame, screen, title,
                    "This will destroy " + listed(several) + ". This action cannot be undone.", modal.warn());
            return;
        }
        var isAllTemplates = "--all".equals(pendingDeleteName);
        var isAllInstances = "--all-instances".equals(pendingDeleteName);
        var isAll = isAllTemplates || isAllInstances;
        var title = isAllTemplates ? " Destroy all templates "
                : isAllInstances ? " Destroy all instances "
                : " Destroy '" + pendingDeleteName + "' ";
        var message = isAllTemplates ? "This will destroy all built templates."
                : isAllInstances ? "This will destroy all instances."
                : "This action cannot be undone." + pendingDeleteNote;
        modal.renderConfirmModal(frame, screen, title, message, modal.warn());
    }

    /** The names, comma separated; past {@link #LISTED} of them, the rest are counted. */
    static String listed(List<String> names) {
        if (names.size() <= LISTED) return String.join(", ", names);
        return String.join(", ", names.subList(0, LISTED - 1)) + " and " + (names.size() - LISTED + 1) + " more";
    }

    private static final int LISTED = 8;
}
