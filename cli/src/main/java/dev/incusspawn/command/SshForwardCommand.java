package dev.incusspawn.command;

import dev.incusspawn.incus.Metadata;
import dev.incusspawn.ssh.SshForwarder;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;

import java.io.OutputStream;

/**
 * The loopback forwarder for SSH clients that ignore {@code ProxyCommand} (#1600): see
 * {@link SshForwarder}. Started detached by the Gateway action where the instance's address
 * cannot be reached; runs as long as the instance does.
 */
@CommandDefinition(
        name = "ssh-forward",
        description = "Loopback SSH forwarder that tunnels through the Incus exec API",
        generateHelp = true
)
public class SshForwardCommand extends BaseCommand {

    @Argument(description = "Instance name", required = true)
    String instance;

    @Override
    protected CommandResult doExecute() throws Exception {
        var incus = dev.incusspawn.RuntimeServices.incus();
        var metadata = incus.instanceMetadata(instance);
        var status = metadata.path("status").asText("");
        if (!"Running".equalsIgnoreCase(status)) {
            System.err.println("Error: instance '" + instance + "' is not running"
                    + (status.isEmpty() ? "." : " (status: " + status + ")."));
            return CommandResult.valueOf(1);
        }

        // The port it had before, so the key pinned there stays valid across restarts.
        int stamped = parsePort(metadata.path("config").path(Metadata.SSH_FORWARD_PORT).asText(""));
        try (var server = SshForwarder.Server.bind(instance, stamped,
                (in, out) -> SshProxyCommand.pipeToSshd(incus, instance, in, out, OutputStream.nullOutputStream()),
                () -> status(incus), () -> hostKey(incus), SshForwarder.POLL)) {
            if (server.port() != stamped) {
                incus.configSet(instance, Metadata.SSH_FORWARD_PORT, String.valueOf(server.port()));
            }
            System.err.println("Forwarding 127.0.0.1:" + server.port() + " to " + instance + ":22");
            System.err.println("Stopped: " + server.serve());
        }
        return CommandResult.SUCCESS;
    }

    /** "" only when Incus says the instance is gone; a request that fails throws, which the server tolerates. */
    private String status(dev.incusspawn.incus.IncusClient incus) {
        var metadata = incus.instanceMetadataOrThrow(instance);
        return metadata == null ? "" : metadata.path("status").asText("");
    }

    /** Read through the exec API, which Incus authenticates: no first contact to trust. */
    private String hostKey(dev.incusspawn.incus.IncusClient incus) {
        try {
            var result = incus.shellExec(instance, "cat", SshForwarder.HOST_KEY_FILE);
            if (!result.success()) {
                dev.incusspawn.Warnings.warn("host key not pinned: " + result.stderr().strip());
                return null;
            }
            var key = SshForwarder.hostKeyOf(result.stdout());
            if (key != null) return key;
            dev.incusspawn.Warnings.warn("host key not pinned: no ed25519 key in " + SshForwarder.HOST_KEY_FILE);
        } catch (RuntimeException e) {
            dev.incusspawn.Warnings.warn("host key not pinned: " + e.getMessage());
        }
        return null;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.strip());
            return port > 0 && port < 65536 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
