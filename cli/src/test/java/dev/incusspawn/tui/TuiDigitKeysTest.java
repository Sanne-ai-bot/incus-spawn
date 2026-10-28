package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keys for keyboards without an F row (#1824): a digit is its F-key, 0 being F10, and Alt with a
 * digit is Shift with that F-key, on every keyboard layout. A mode that reads digits itself, a text
 * field or the build menu's numbered options, keeps them.
 */
@ExtendWith(IsolatedHome.class)
class TuiDigitKeysTest {

    private static final KeyEvent TAB = KeyEvent.ofKey(KeyCode.TAB);
    private static final KeyEvent DOWN = KeyEvent.ofKey(KeyCode.DOWN);
    private static final KeyEvent ESC = KeyEvent.ofKey(KeyCode.ESCAPE);

    private TuiRunner runner;
    private final FakeIncusDaemon daemon = new FakeIncusDaemon();
    /** Submitted background tasks wait here until the test runs them. */
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final BackgroundTaskManager tasks = new BackgroundTaskManager(queued::add);

    @BeforeEach
    void runner() throws Exception {
        runner = TuiRunner.create(TuiConfig.builder().backend(new HeadlessBackend(80, 24))
                .shutdownHook(false).noTick().build());
    }

    @AfterEach
    void close() {
        runner.close();
    }

    private static KeyEvent digit(char c) {
        return KeyEvent.ofChar(c);
    }

    private static KeyEvent altDigit(char c) {
        return KeyEvent.ofChar(c, KeyModifiers.ALT);
    }

    private static Map<String, String> branch() {
        var config = new HashMap<String, String>();
        config.put(Metadata.TYPE, "branch");
        config.put(Metadata.PROFILE, "tpl-minimal");
        config.put(Metadata.PARENT, "tpl-minimal");
        config.put(Metadata.NETWORK_MODE, "proxy");
        return config;
    }

    /** tpl-minimal built; alpha running and gamma stopped, in that order. */
    private Tui tui() {
        daemon.container("tpl-minimal", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-minimal"))
                .instance("alpha", "container", "Running", branch())
                .instance("gamma", "container", "Stopped", branch());
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }

            @Override
            String currentVersion() {
                return "1.2.3";
            }
        };
        var def = new ImageDef();
        def.setName("tpl-minimal");
        def.setImage("fedora-44");
        def.setDescription("Minimal Fedora");
        var defs = new LinkedHashMap<String, ImageDef>();
        defs.put("tpl-minimal", def);
        tui.useDefinitions(defs, new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), tasks, new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));
        return tui;
    }

    private void press(Tui tui, KeyEvent... keys) {
        for (var key : keys) tui.handleEvent(key, runner);
    }

    private static String screen(Tui tui) {
        return TuiSnapshot.toText(TuiSnapshot.render(80, 24, tui::render));
    }

    @Test
    void aDigitOpensWhatItsFunctionKeyOpens() {
        var tui = tui();
        press(tui, digit('1'));
        assertEquals(Tui.Mode.INFO, tui.mode(), "1 is F1");
        press(tui, digit('1'));
        assertEquals(Tui.Mode.BROWSE, tui.mode(), "and closes what F1 opened, as F1 does");

        press(tui, digit('3'));
        assertEquals(Tui.Mode.TEMPLATE_DETAIL, tui.mode(), "3 is F3");
        press(tui, ESC);
        press(tui, digit('5'));
        assertEquals(Tui.Mode.BUILD_MENU, tui.mode(), "5 is F5");
        press(tui, ESC);
        press(tui, digit('4'));
        assertEquals(Tui.Mode.BRANCH, tui.mode(), "4 is F4");
        press(tui, ESC);

        press(tui, TAB, digit('3'));
        assertEquals(Tui.Mode.INSTANCE_DETAIL, tui.mode(), "3 is F3 on the instances too");
        press(tui, ESC);
        press(tui, digit('9'));
        assertEquals(Tui.Mode.BROWSE, tui.mode());
        assertTrue(screen(tui).contains("No actions available for alpha"), "9 is F9");
        press(tui, digit('8'));
        assertEquals(Tui.Mode.CONFIRM_DELETE, tui.mode(), "8 is F8");
        assertEquals("alpha", tui.deleteConfirm().name());
    }

    @Test
    void sevenStopsTheRunningRowAsF7Does() {
        var tui = tui();
        press(tui, TAB, digit('7'));
        runQueued();
        assertEquals(List.of("alpha stop"), daemon.stateActions());
    }

    @Test
    void altWithADigitIsShiftWithItsFunctionKey() {
        var tui = tui();
        press(tui, TAB, altDigit('7'));
        // Not run: a restart waits for the guest, which this daemon does not bring up
        assertEquals(List.of("Restarting alpha"),
                tasks.getActiveTasks().stream().map(BackgroundTask::displayName).toList(), "Alt+7 is Shift+F7");

        var other = tui();
        press(other, TAB, altDigit('8'));
        assertEquals(Tui.Mode.CONFIRM_DELETE, other.mode(), "Alt+8 is Shift+F8");
        assertEquals("--all-instances", other.deleteConfirm().name());
    }

    @Test
    void zeroQuits() {
        var tui = tui();
        press(tui, digit('0'));
        assertEquals(Tui.PendingAction.NONE, tui.pendingAction());
        assertFalse(runner.isRunning(), "0 is F10");
    }

    @Test
    void aTextFieldKeepsItsDigits() {
        var tui = tui();
        press(tui, TAB, DOWN, digit('6'));
        assertEquals(Tui.Mode.RENAME, tui.mode(), "6 is F6 on the stopped gamma");
        press(tui, digit('1'), altDigit('2'));
        assertEquals(Tui.Mode.RENAME, tui.mode());
        assertTrue(screen(tui).contains("gamma1"), "a digit is typed into the name: " + screen(tui));
    }

    @Test
    void theSearchBarKeepsItsDigits() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('/'), digit('3'));
        assertEquals(Tui.Mode.BROWSE, tui.mode(), "no details open while typing a search");
        assertTrue(screen(tui).contains(" / 3"), screen(tui));
    }

    @Test
    void theBuildMenuKeepsItsNumberedOptions() {
        var tui = tui();
        press(tui, digit('5'), digit('1'));
        assertEquals(Tui.PendingAction.BUILD_TEMPLATE, tui.pendingAction(), "1 picks the menu's first option");
    }

    @Test
    void ctrlWithADigitIsLeftAlone() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('1', KeyModifiers.CTRL));
        assertEquals(Tui.Mode.BROWSE, tui.mode());
    }

    @Test
    void shiftOnADigitIsHowSomeLayoutsTypeIt() {
        var tui = tui();
        press(tui, KeyEvent.ofChar('1', KeyModifiers.SHIFT));
        assertEquals(Tui.Mode.INFO, tui.mode(), "AZERTY types 1 with Shift");
    }

    private void runQueued() {
        while (!queued.isEmpty()) queued.remove().run();
    }
}
