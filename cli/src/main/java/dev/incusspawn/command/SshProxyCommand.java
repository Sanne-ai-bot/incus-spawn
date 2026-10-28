package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;

@CommandDefinition(
        name = "ssh-proxy",
        description = "SSH ProxyCommand that tunnels through the Incus exec API",
        generateHelp = true
)
public class SshProxyCommand extends BaseCommand {

    @Argument(description = "Instance name", required = true)
    String instance;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = dev.incusspawn.RuntimeServices.incus();

        if (!checkInstanceRunning(incus)) {
            return CommandResult.valueOf(1);
        }

        int exitCode = incus.execBidirectional(instance, 0, 0, "/",
                guestCommand(),
                System.in, System.out, System.err);
        return CommandResult.valueOf(exitCode);
    }

    /** A socket listening on port 22 (hex {@code 0016}), as a line of {@code /proc/net/tcp} or {@code tcp6} shows it. */
    static final String SSHD_LISTENING = ":0016 [0-9A-F]*:0000 0A";
    /** At most how many seconds the guest waits for sshd to listen before connecting anyway. */
    static final int SSHD_WAIT_SECONDS = 20;

    /**
     * What the exec runs in the instance: the end of the ssh connection.
     * <p>
     * A start returns once the guest answers an exec, about a second before sshd listens, so an
     * immediate {@code ssh} would be refused (#1699). The wait is here rather than on every start:
     * only an SSH connection pays it. It has to happen before {@code nc} runs, since ssh has already
     * written its banner to stdin and a retried exec would lose it. The probe reads the kernel's
     * socket table, so it costs one {@code grep} when sshd is up and logs no connection in sshd.
     * It waits only while systemd is still starting the guest: after that a stopped or failed sshd
     * will not come, and {@code nc} reports the refusal at once, as before. The deadline is wall
     * clock, since early in boot a {@code systemctl} call to a busy PID 1 can take far longer than
     * a pass's sleep; it is set on the first miss, so a ready instance forks no {@code date}.
     */
    static String[] guestCommand() {
        return new String[]{"sh", "-c",
                "end=; until grep -qs '" + SSHD_LISTENING + "' /proc/net/tcp6 /proc/net/tcp; do"
                        + " case $(systemctl is-system-running 2>/dev/null) in initializing|starting) ;; *) break ;; esac;"
                        + " now=$(date +%s); : ${end:=$((now + " + SSHD_WAIT_SECONDS + "))}; [ $now -ge $end ] && break;"
                        + " sleep 0.1; done;"
                        + " exec nc localhost 22"};
    }

    /**
     * Whether the instance runs, saying why not as an error: ssh shows the ProxyCommand's stderr,
     * and an error writes nothing to stdout, which is the connection's stream.
     */
    boolean checkInstanceRunning(dev.incusspawn.incus.IncusClient incus) {
        var shown = OutputFormat.oneLine(instance);
        try {
            var status = incus.getInstanceStatus(instance);
            if (status.isEmpty()) {
                BuildOutput.error("instance '" + shown + "' not found or not accessible.");
                return false;
            }
            if (!"Running".equalsIgnoreCase(status)) {
                BuildOutput.error("instance '" + shown + "' is not running (status: " + OutputFormat.oneLine(status) + ").",
                        "Start it first: isx shell " + shown);
                return false;
            }
            return true;
        } catch (Exception e) {
            BuildOutput.error("could not check instance status: " + OutputFormat.oneLine(failure(e)));
            return false;
        }
    }
}
