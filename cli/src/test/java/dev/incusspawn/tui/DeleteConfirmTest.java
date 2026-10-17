package dev.incusspawn.tui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The delete confirmation on its own: only y confirms, and what it says it will destroy. */
class DeleteConfirmTest {

    private static DeleteConfirm dialog(String name, String note) {
        return new DeleteConfirm(new ModalRenderer(TuiTheme.dark()), name, note);
    }

    @Test
    void onlyYConfirms() {
        var dialog = dialog("dev-1", "");
        assertEquals(DeleteConfirm.Outcome.CONFIRM, dialog.handleKey(KeyEvent.ofChar('y')));
        assertEquals(DeleteConfirm.Outcome.CONFIRM, dialog.handleKey(KeyEvent.ofChar('Y')));
        assertEquals(DeleteConfirm.Outcome.CLOSE, dialog.handleKey(KeyEvent.ofChar('n')));
        assertEquals(DeleteConfirm.Outcome.CLOSE, dialog.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(DeleteConfirm.Outcome.CLOSE, dialog.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals("dev-1", dialog.name());
    }

    @Test
    void rendersOneInstanceWithItsNote() {
        var dialog = dialog("dev-1", " Space reclaimed may be less than 1.2 GiB.");
        TuiSnapshot.assertMatches("delete-confirm-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }

    @Test
    void rendersAllTemplates() {
        var dialog = dialog("--all", "");
        TuiSnapshot.assertMatches("delete-confirm-all-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }

    @Test
    void rendersAllInstances() {
        var dialog = dialog("--all-instances", "");
        TuiSnapshot.assertMatches("delete-confirm-all-instances-80x24",
                TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }

    @Test
    void rendersTheMarkedInstances() {
        var dialog = DeleteConfirm.ofSeveral(new ModalRenderer(TuiTheme.dark()), List.of("alpha", "gamma"), DeleteConfirm.Kind.INSTANCE);
        assertNull(dialog.name(), "several names are not one target");
        assertEquals(List.of("alpha", "gamma"), dialog.several());
        TuiSnapshot.assertMatches("delete-confirm-several-80x24",
                TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }

    @Test
    void listsAtMostEightNamesAndCountsTheRest() {
        assertEquals("a, b", DeleteConfirm.listed(List.of("a", "b")));
        var nine = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i");
        assertEquals("a, b, c, d, e, f, g and 2 more", DeleteConfirm.listed(nine));
        assertEquals("a, b, c, d, e, f, g, h", DeleteConfirm.listed(nine.subList(0, 8)));
    }
}
