package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * When {@code isx templates edit} stops editing a file it left invalid. Before, the end of piped
 * stdin answered "Re-edit?" yes, and the editor reopened for ever.
 */
@ExtendWith(IsolatedHome.class)
class TemplatesEditLoopTest {

    /** An editor that leaves the file invalid, and fails the test past a few passes rather than hang. */
    private static TemplatesCommand.Editor leavesItInvalid(int[] passes) {
        return file -> {
            if (++passes[0] > 3) throw new AssertionError("the editor reopened " + passes[0] + " times");
            Files.writeString(file, "image: images:fedora/44\n"); // no name: an error
            return 0;
        };
    }

    private static Path builtinCopy() throws Exception {
        var images = Files.createDirectories(Environment.configDir().resolve("images"));
        return Files.writeString(images.resolve("tpl-copy.yaml"), "name: tpl-copy\nimage: images:fedora/44\n");
    }

    @Test
    void theEndOfInputStopsEditingAndRemovesTheBrokenCopy() throws Exception {
        var file = builtinCopy();
        var passes = new int[1];
        var prompts = new ScriptedPrompts(); // nothing scripted: the first read is the end of input

        var valid = new boolean[] {true};
        RecordingOutput.during(() -> valid[0] = TemplatesCommand.editLoop(file, null, true, leavesItInvalid(passes), () -> prompts));

        assertFalse(valid[0]);
        assertEquals(1, passes[0], "one pass, then the end of input stops it");
        assertFalse(Files.exists(file), "the broken copy of a built-in is removed");
    }

    @Test
    void withNoTerminalToAskOnItStopsAndRemovesTheBrokenCopy() throws Exception {
        var file = builtinCopy();
        var passes = new int[1];

        var valid = new boolean[] {true};
        RecordingOutput.during(() -> valid[0] = TemplatesCommand.editLoop(file, null, true, leavesItInvalid(passes), () -> null));

        assertFalse(valid[0]);
        assertEquals(1, passes[0]);
        assertFalse(Files.exists(file), "removed whichever way editing stops");
    }
}
