package dev.incusspawn.command;

import dev.incusspawn.mcp.TaskJoin;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@code isx shell} reads {@code --task} (with an id, without one, not at all), and what it prints when it joins nothing. */
class ShellCommandTaskTest {

    @CommandDefinition(name = "shell", description = "")
    public static class Parsed extends ShellCommand {
        static String name;
        static boolean task;
        static String taskId;

        @Override
        protected CommandResult doExecute() {
            Parsed.name = this.name;
            Parsed.task = this.task;
            Parsed.taskId = this.taskId;
            return CommandResult.SUCCESS;
        }
    }

    private static void parse(String... args) {
        Parsed.name = null;
        Parsed.task = false;
        Parsed.taskId = null;
        AeshRuntimeRunner.builder().command(Parsed.class).args(args).execute();
    }

    @Test
    void anIdAfterTaskNamesIt() {
        parse("box", "--task", "t3-abcd");
        assertEquals("box", Parsed.name);
        assertTrue(Parsed.task);
        assertEquals("t3-abcd", Parsed.taskId);
        parse("--task", "box", "t3-abcd");
        assertEquals("box", Parsed.name);
        assertEquals("t3-abcd", Parsed.taskId);
    }

    @Test
    void taskAloneAsksForTheOnlyOne() {
        parse("box", "--task");
        assertEquals("box", Parsed.name);
        assertTrue(Parsed.task);
        assertNull(Parsed.taskId);
    }

    @Test
    void withoutTaskItIsAShell() {
        parse("box");
        assertEquals("box", Parsed.name);
        assertFalse(Parsed.task);
        assertNull(Parsed.taskId);
    }

    @Test
    void severalTasksArePrintedEachWithTheCommandThatJoinsIt() {
        var entries = List.of(
                new TaskJoin.Entry("t1-abcd", "claude", "finished", Instant.ofEpochSecond(0), "Fix the flaky test", "s1", "/a", null),
                new TaskJoin.Entry("t2-abcd", "codex", "running", null, "", "s2", "/b", null));
        var out = new ByteArrayOutputStream();
        ShellCommand.explain(new TaskJoin.Outcome.Several(entries), "box", Instant.ofEpochSecond(7200), new PrintStream(out, true, StandardCharsets.UTF_8));
        assertEquals("""
                2 delegated tasks in box; join one:
                  t1-abcd  claude  finished 2h ago  Fix the flaky test
                    isx shell box --task t1-abcd
                  t2-abcd  codex  running
                    isx shell box --task t2-abcd
                """, out.toString(StandardCharsets.UTF_8));
    }
}
