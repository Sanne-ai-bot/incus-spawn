package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.StaticIpAllocator;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.GuiPassthrough;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.InstanceNetwork;
import dev.incusspawn.lifecycle.ZmxSocketForward;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;

import java.util.function.Consumer;

/**
 * Shared instance preparation logic for shell and run commands.
 * Handles validation, network checks, instance startup, and GUI passthrough.
 */
public class InstancePrep {

    /**
     * Prepare an instance for use: validate it exists, check proxy/network,
     * start if stopped, and verify GUI health.
     *
     * @param incus the Incus client
     * @param name the instance name
     * @return the parent template name, or null if validation fails
     */
    public static String prepareInstance(IncusClient incus, String name) {
        String templateName;
        try {
            templateName = prepare(incus, name, System.out::println);
        } catch (Refused e) {
            if (!e.reported()) e.getMessage().lines().forEach(l -> System.err.println(l));
            return null;
        }
        GuiPassthrough.checkGuiHealth(incus, name);
        return templateName;
    }

    /** Why {@link #prepare} refused, as lines for the user; {@code reported} when already printed. */
    public static final class Refused extends RuntimeException {
        private final boolean reported;

        Refused(String message, boolean reported) {
            super(message);
            this.reported = reported;
        }

        public boolean reported() {
            return reported;
        }
    }

    /**
     * {@link #prepareInstance} without the GUI check, for a caller with no terminal ({@code isx
     * mcp}'s {@code start_instance}): refuses by throwing {@link Refused} rather than printing and
     * returning null. {@code say} gets {@link InstanceLifecycle#ensureReady}'s progress; the repairs
     * before it (which may start the instance to fix its CA) print as {@code isx shell} does.
     *
     * @return the leaf template the instance descends from
     */
    public static String prepare(IncusClient incus, String name, java.util.function.Consumer<String> say) {
        if (!incus.exists(name)) {
            throw new Refused("Error: no instance named '" + name + "' found.\n"
                    + "Run 'isx list' to see available instances.", false);
        }

        // Validate parent template before any side effects
        var parent = incus.configGet(name, Metadata.PARENT);
        if (parent == null || parent.isEmpty()) {
            throw new Refused("Error: instance '" + name + "' has no parent template.\n"
                    + "This does not appear to be an incus-spawn managed instance.", false);
        }

        // Prefer PROFILE (always the leaf template name) for chain resolution;
        // PARENT may point to a clone when branching from another clone.
        var profile = incus.configGet(name, Metadata.PROFILE);
        var templateName = (profile != null && !profile.isEmpty()) ? profile : parent;

        var networkMode = incus.configGet(name, Metadata.NETWORK_MODE);
        var machineType = incus.machineType(name);
        if (!NetworkMode.AIRGAP.name().equals(networkMode)) {
            if (!ProxyHealthCheck.checkOrWarn(incus)) {
                // checkOrWarn printed why; the reason may be DNS or a stale gateway as well as a stop.
                throw new Refused("the isx proxy is not ready to serve this instance; 'isx doctor' says why.", true);
            }
            BridgeSubnetCheck.warnIfConflict(incus);
            FirewallDetector.warnIfNotRunning();
            fixStaticIpMismatch(incus, name, machineType);
            fixCaMismatch(incus, name, machineType);
            fixResolvConfMismatch(incus, name);
        }

        // Read after the repair above, so a VM it just reassigned is seen to owe its file
        var instance = incus.instanceMetadata(name);
        readyForUse(incus, name, instance, machineType, say, msg -> System.err.println("Warning: " + msg));
        return templateName;
    }

    /**
     * The last step before a shell or a command, once the terminal is the caller's to print on:
     * start the instance if it is still stopped, bring its credential identities in line with its
     * account pins, and recreate the host's zmx symlink to its session socket. The TUI calls this
     * after releasing the terminal, as {@code isx shell} does at the end of {@link #prepare}.
     *
     * <p>The reconcile is what makes an account changed on a stopped instance take effect on its
     * next use ({@link InstanceLifecycle#reconcileAccountIdentities}); the TUI's shell used to
     * skip it, and then pushed as the new account and committed as the old one (#907).
     *
     * @param instance the instance as read after the repairs before it
     * @param say      progress, from the start and the reconcile
     * @param warn     the reconcile's warnings
     */
    public static void readyForUse(IncusClient incus, String name, JqValue instance, MachineType machineType,
                                   Consumer<String> say, Consumer<String> warn) {
        InstanceLifecycle.ensureReady(incus, name, instance, machineType, say);
        // From the same read: what ensureReady writes is none of what the reconcile looks at
        InstanceLifecycle.reconcileAccountIdentities(incus, name, instance, say, warn);
        ZmxSocketForward.ensureSymlink(name);
    }

    private static void fixStaticIpMismatch(IncusClient incus, String name, MachineType machineType) {
        if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return;
        try {
            if (InstanceNetwork.fixStaticIpIfNeeded(incus, name, StaticIpAllocator.Output.TERMINAL, machineType)) {
                BuildOutput.warnBanner("Static IP mismatch",
                        "Reassigned to current bridge subnet.");
            }
        } catch (Exception e) {
            System.err.println("Warning: could not repair static IP for " + name
                    + ": " + e.getMessage());
        }
    }

    private static void fixResolvConfMismatch(IncusClient incus, String name) {
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(name))) return;
        try {
            if (ProxyConfig.fixResolvConfIfNeeded(incus, name)) {
                BuildOutput.warnBanner("DNS configuration mismatch",
                        "Updated /etc/resolv.conf automatically.");
            }
        } catch (Exception ignored) {
        }
    }

    private static void fixCaMismatch(IncusClient incus, String container, MachineType machineType) {
        // Ensure the container is running so we can push the cert
        JqValue started = null;
        if ("Stopped".equalsIgnoreCase(incus.getInstanceStatus(container))) {
            started = InstanceLifecycle.startForUse(incus, container, machineType, System.err::println);
        }

        // The start's own read of the instance, when there was one: no need to read it again
        if (CertificateAuthority.fixContainerCaIfNeeded(incus, container, started, BuildOutput::warn)) {
            BuildOutput.warnBanner("CA certificate mismatch",
                    "Updated automatically.");
        }
    }
}
