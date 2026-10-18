package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.NoTerminalForShellException;
import dev.incusspawn.mcp.TaskJoin;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ShellMenu;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

@CommandDefinition(
        name = "shell",
        description = "Open a shell in an existing clone",
        generateHelp = true
)
public class ShellCommand extends BaseCommand {

    @Argument(index = "0", description = "Name of the clone to connect to", required = true)
    String name;

    @Option(name = "task", hasValue = false,
            description = "Join a delegated task's agent conversation instead of opening a shell")
    boolean task;

    @Argument(index = "1", paramLabel = "task-id", required = false,
            description = "With --task: which task (from task_status.join_command, or the list --task prints)")
    String taskId;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (taskId != null && !task) {
            System.err.println("A task id needs --task: isx shell " + name + " --task " + taskId);
            return CommandResult.valueOf(1);
        }
        // Refuse before starting the instance: without a terminal there is no shell to open.
        if (!IncusClient.hasTerminal()) throw new NoTerminalForShellException(name);
        var incus = RuntimeServices.incus();

        var parent = InstancePrep.prepareInstance(incus, name);
        if (parent == null) {
            return CommandResult.valueOf(1);
        }

        // Resolved before the shell is prepared: a refusal needs none of it.
        var command = task ? joinCommand(incus) : null;
        if (task && command == null) return CommandResult.valueOf(1);
        var config = incus.configByPrefix(name, "");
        var prep = IncusClient.ShellPrep.fromConfig(incus, config);
        if (command != null) {
            prep = prep.withCommandOnly(command);
        } else if (config.containsKey(Metadata.MCP_OWNER)) {
            // Only an isx mcp instance can hold a delegated task: one guest read, for it alone.
            var hint = hint(incus);
            if (hint != null) System.out.println(hint);
        }

        System.out.println("Connecting to " + name + "...\n");
        var menu = ShellMenu.NONE;
        if (ShellMenu.enabled()) {
            menu = new ActionResolver(incus, RuntimeServices.toolDefLoader(),
                    RuntimeServices.toolSetups(), ImageDef.loadAll(w -> {}))
                    .shellMenu(name, prep.templateName(), prep.workdir());
        }
        incus.interactiveShell(name, "agentuser", prep, menu);
        return CommandResult.SUCCESS;
    }

    /** The command resuming the task {@code --task} names, or null after saying why there is none. */
    private String joinCommand(IncusClient incus) {
        List<TaskJoin.Entry> entries;
        try {
            entries = TaskJoin.read(incus, name);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            return null;
        }
        var outcome = TaskJoin.resolve(entries, taskId);
        if (outcome instanceof TaskJoin.Outcome.Join join) {
            System.out.println("Joining " + join.entry().id() + " (" + join.entry().agent() + ") in " + name + ".");
            return join.entry().guestCommand();
        }
        explain(outcome, name, Instant.now(), System.err);
        if (outcome instanceof TaskJoin.Outcome.Busy busy && busy.entry().running()) {
            var recent = TaskJoin.recent(incus, name, busy.entry(), 5);
            if (!recent.isEmpty()) {
                System.err.println("Lately it:");
                recent.forEach(line -> System.err.println("  " + line));
            }
        }
        return null;
    }

    /** Why {@code outcome} joins nothing, and what to run instead. */
    static void explain(TaskJoin.Outcome outcome, String name, Instant now, PrintStream out) {
        if (!(outcome instanceof TaskJoin.Outcome.Several several)) {
            var why = TaskJoin.refusal(outcome, name);
            if (why != null) out.println(why);
            return;
        }
        out.println(several.entries().size() + " delegated tasks in " + name + "; join one:");
        for (var entry : several.entries()) {
            out.println("  " + TaskJoin.describe(entry, now));
            out.println("    " + TaskJoin.hostCommand(name, entry.id()));
        }
    }

    /** The one hint line for a plain shell, or null: never a reason not to open it. */
    private String hint(IncusClient incus) {
        try {
            return TaskJoin.hint(name, TaskJoin.read(incus, name));
        } catch (RuntimeException e) {
            return null;
        }
    }

}
