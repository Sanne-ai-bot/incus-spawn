package dev.incusspawn.tui;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The branch dialog on its own: which keys it keeps, which it hands back, and the request it describes. */
class BranchModalTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static BranchModal dialog(MachineType type) {
        return new BranchModal(new ModalRenderer(THEME), THEME, "tpl-dev", "dev-1",
                new BranchFlow.Defaults(type, false, false, null, null),
                new BranchAccountChoices(new ModalRenderer(THEME), THEME, List.of()));
    }

    /** A VM's dialog with its limits given, rather than this host's defaults. */
    private static BranchModal vmDialog(BranchModal.VmLimits limits) {
        return new BranchModal(new ModalRenderer(THEME), THEME, "tpl-dev", "dev-1",
                new BranchFlow.Defaults(MachineType.VM, false, false, null, null),
                new BranchAccountChoices(new ModalRenderer(THEME), THEME, List.of()), limits);
    }

    private static BranchModal.Outcome press(BranchModal dialog, KeyEvent... keys) {
        var outcome = BranchModal.Outcome.HANDLED;
        for (var key : keys) outcome = dialog.handleKey(key);
        return outcome;
    }

    @Test
    void escapeClosesAndEnterIsTheTuisToConfirm() {
        assertEquals(BranchModal.Outcome.CLOSE, press(dialog(MachineType.CONTAINER), KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(BranchModal.Outcome.CONFIRM, press(dialog(MachineType.CONTAINER), KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void theNameTakesLettersDigitsAndHyphensOnly() {
        var dialog = dialog(MachineType.CONTAINER);
        press(dialog, KeyEvent.ofChar('x'), KeyEvent.ofChar('_'), KeyEvent.ofChar('-'), KeyEvent.ofChar('2'));
        assertEquals("dev-1x-2", dialog.name());
    }

    @Test
    void theRequestCarriesWhatTheFieldsSay() {
        var dialog = dialog(MachineType.CONTAINER);
        // Tab to GUI, toggle it; Tab past KVM to the network, cycle it once.
        press(dialog, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar(' '),
                KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar(' '));
        var request = dialog.request("dev-2");
        assertEquals("dev-2", request.name());
        assertEquals("tpl-dev", request.source());
        assertEquals(Boolean.TRUE, request.gui(), "a box changed from its default is passed on");
        assertEquals(NetworkMode.values()[1], request.networkMode());
        assertNull(request.inbox());
        assertNull(request.kvm(), "an untouched box leaves KVM to the default, so create() says why it is off (#1254)");
    }

    @Test
    void aVmsCpuLimitMustBeANumber() {
        var dialog = dialog(MachineType.VM);
        // The CPU field follows the name; clear it and type a letter.
        press(dialog, KeyEvent.ofKey(KeyCode.TAB));
        for (int i = 0; i < 4; i++) press(dialog, KeyEvent.ofKey(KeyCode.BACKSPACE));
        press(dialog, KeyEvent.ofChar('x'));
        var e = assertThrows(BranchFlow.BranchException.class, () -> dialog.request("dev-2"));
        assertEquals("CPU limit 'x' is not a number.", e.getMessage());
    }

    @Test
    void rendersAContainerBranch() {
        var dialog = dialog(MachineType.CONTAINER);
        TuiSnapshot.assertMatches("branch-modal-container-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }

    @Test
    void rendersAVmBranchWithItsLimits() {
        // Given rather than this host's defaults, in the shapes BranchFlow's defaults take (#1251).
        var dialog = vmDialog(new BranchModal.VmLimits("4", "13113MiB", "100GB"));
        TuiSnapshot.assertMatches("branch-modal-vm-80x24", TuiSnapshot.render(80, 24, f -> dialog.render(f, f.area())));
    }
}
