package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.NoTerminalForShellException;
import dev.incusspawn.mcp.TaskJoin;
import dev.incusspawn.tool.ActionResolver;
import dev.incusspawn.tool.ShellMenu;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.time.Instant;
import java.util.List;

@CommandDefinition(
        name = "shell",
        description = "Open a shell in an existing clone",
        generateHelp = true
)
public class ShellCommand extends BaseCommand {

    @Argument(index = "0", completer = Completers.Instance.class, description = "Name of the clone to connect to", required = true)
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
            BuildOutput.error("a task id needs --task.", "isx shell " + name + " --task " + taskId);
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
        var joined = task ? toJoin(incus) : null;
        if (task && joined == null) return CommandResult.valueOf(1);
        // Names this join, so a refusal in the guest can be told from the conversation ending.
        var nonce = joined != null ? TaskJoin.nonce() : null;
        var command = joined != null ? joined.guestCommand(nonce) : null;
        var config = incus.configByPrefix(name, "");
        var prep = IncusClient.ShellPrep.fromConfig(incus, config);
        if (command != null) {
            prep = prep.withCommandOnly(command);
        } else if (config.containsKey(Metadata.MCP_OWNER)) {
            // Only an isx mcp instance can hold a delegated task: one guest read, for it alone.
            var hint = hint(incus);
            if (hint != null) BuildOutput.step(hint);
        }

        BuildOutput.step("Connecting to " + name);
        var menu = ShellMenu.NONE;
        if (ShellMenu.enabled()) {
            menu = new ActionResolver(incus, RuntimeServices.toolDefLoader(),
                    RuntimeServices.toolSetups(), ImageDef.loadAll(w -> {}))
                    .shellMenu(name, prep.templateName(), prep.workdir());
        }
        incus.interactiveShell(name, "agentuser", prep, menu);
        // The guest said why on the terminal already; its exit status does not reach the host.
        if (joined != null && TaskJoin.refusedInGuest(incus, name, joined.id(), nonce)) return CommandResult.valueOf(1);
        return CommandResult.SUCCESS;
    }

    /** The task {@code --task} names, or null after saying why there is none to join. */
    private TaskJoin.Entry toJoin(IncusClient incus) {
        List<TaskJoin.Entry> entries;
        try {
            entries = TaskJoin.read(incus, name);
        } catch (RuntimeException e) {
            BuildOutput.error(failure(e));
            return null;
        }
        var outcome = TaskJoin.resolve(entries, taskId);
        if (outcome instanceof TaskJoin.Outcome.Join join) {
            BuildOutput.step("Joining " + join.entry().id() + " (" + join.entry().agent() + ") in " + name + ".");
            return join.entry();
        }
        var recent = outcome instanceof TaskJoin.Outcome.Busy busy && busy.entry().running()
                ? recent(incus, busy.entry()) : List.<String>of();
        explain(outcome, name, Instant.now(), recent, BuildOutput.bound());
        return null;
    }

    /**
     * Why {@code outcome} joins nothing, and what to run instead, as an error: each task with the
     * command that joins it, or what a busy one did {@code recent}ly.
     */
    static void explain(TaskJoin.Outcome outcome, String name, Instant now, List<String> recent, Output out) {
        if (outcome instanceof TaskJoin.Outcome.Several several) {
            out.error(several.entries().size() + " delegated tasks in " + name + "; join one:", several.entries().stream()
                    .map(entry -> TaskJoin.describe(entry, now) + ": " + TaskJoin.hostCommand(name, entry.id()))
                    .toArray(String[]::new));
            return;
        }
        var why = TaskJoin.refusal(outcome, name);
        if (why == null) return;
        out.error(recent.isEmpty() ? why : why + " Lately it:", recent.toArray(String[]::new));
    }

    /** What a busy task did lately, or nothing when the guest cannot say: never in the way of why it is busy. */
    private List<String> recent(IncusClient incus, TaskJoin.Entry entry) {
        try {
            return TaskJoin.recent(incus, name, entry, 5);
        } catch (RuntimeException e) {
            return List.of();
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
