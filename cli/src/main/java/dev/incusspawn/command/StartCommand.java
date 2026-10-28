package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tui.InstanceLockManager;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;

/**
 * {@code isx start <name>}: make an instance ready for use without opening a shell (#1720), for a
 * script or an orchestrator resuming a workspace. It is what {@code isx shell} does before the
 * shell ({@link InstanceStart}), so the instance gets this start's secret and credential proofs;
 * one already running is checked and left running.
 */
@CommandDefinition(
        name = "start",
        description = "Start an instance and make it ready for use, without opening a shell",
        generateHelp = true
)
public class StartCommand extends BaseCommand {

    @Argument(completer = Completers.Instance.class, description = "Name of the instance to start", required = true)
    String name;

    /** The preparation; {@link InstanceStart.Preparer#FOR_USE} but in tests. */
    InstanceStart.Preparer preparer = InstanceStart.Preparer.FOR_USE;

    @Override
    protected CommandResult doExecute() throws Exception {
        return start(RuntimeServices.incus(), RuntimeServices.lockManager());
    }

    CommandResult start(IncusClient incus, InstanceLockManager locks) throws InterruptedException {
        var shown = OutputFormat.oneLine(name);
        try {
            InstanceStart.start(incus, locks, name, BuildOutput.bound(), preparer, InstanceStart.WAIT);
        } catch (InstancePrep.Refused e) {
            if (!e.reported()) InstancePrep.reportRefusal(e.getMessage());
            return CommandResult.valueOf(1);
        }
        BuildOutput.success(shown + " ready");
        return CommandResult.SUCCESS;
    }
}
