package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * What keeps an airgapped instance airgapped after its branch (#1518). Airgap masks every network
 * device when it branches, but a profile that gains one later attaches it at the next start, so
 * every start for use masks such a device again, as {@link ProxyOnlyAcl#enforce} puts the
 * proxy-only restriction back. Passthrough hardware is refused instead
 * ({@link InstanceNetwork#passthroughRefusal}).
 */
public final class AirgapGuard {

    private AirgapGuard() {}

    /** The instance is airgapped, but has a device isx cannot mask: it must not be started or used. */
    public static final class NotIsolatedException extends IncusException {
        NotIsolatedException(String message) {
            super(message);
        }
    }

    /**
     * Mask the network devices an airgapped {@code instance} has gained since it was branched, or
     * refuse it when it passes hardware through. An instance that gained nothing costs no request:
     * only the instance the caller read is looked at.
     *
     * @throws NotIsolatedException when it passes hardware through, or a device could not be masked
     *         while it runs
     */
    static void enforce(IncusClient incus, String name, JqValue instance, Consumer<String> say) {
        refusePassthrough(name, instance);
        if (NetworkMode.isAirgapped(instance)
                && !InstanceNetwork.expandedDevices(instance, IncusClient::isNetworkDevice).isEmpty()) {
            maskGained(incus, name, say);
        }
    }

    /**
     * @throws NotIsolatedException when {@code instance} is airgapped and passes hardware through
     */
    static void refusePassthrough(String name, JqValue instance) {
        if (!NetworkMode.isAirgapped(instance)) return;
        var refusal = InstanceNetwork.passthroughRefusal(name, "airgapped", instance);
        if (refusal != null) throw new NotIsolatedException(refusal);
    }

    /**
     * Mask the network devices the instance has, from a read of its own, and say which. One that
     * is no longer airgapped, or has nothing left to mask, is left alone.
     */
    private static void maskGained(IncusClient incus, String name, Consumer<String> say) {
        var gained = new AtomicReference<List<String>>(List.of());
        IncusClient.retryWhileChanged(() -> {
            var read = incus.readInstance(name);
            if (read.metadata().isMissingNode()) throw new IncusException("Failed to read instance " + name);
            gained.set(List.of());
            if (!NetworkMode.isAirgapped(read.metadata())) return;
            var found = InstanceNetwork.expandedDevices(read.metadata(), IncusClient::isNetworkDevice);
            if (found.isEmpty()) return;
            var update = new InstanceUpdate();
            InstanceNetwork.maskNics(incus, read.metadata(), update);
            try {
                incus.update(name, read, update);
            } catch (IncusClient.InstanceChangedException e) {
                throw e; // read again
            } catch (IncusException e) {
                if (!"Running".equalsIgnoreCase(read.metadata().pathText("status", ""))) throw e;
                // Incus may refuse to take a device from a running instance, which then keeps it
                throw new NotIsolatedException("'" + name + "' is airgapped, but isx could not mask the network"
                        + " devices it gained (" + String.join(", ", found) + ") while it runs: " + e.getMessage()
                        + "\nIf Incus will not take a device from a running instance, stop it:"
                        + " isx masks the device before its next start.");
            }
            gained.set(found);
        });
        if (gained.get().isEmpty()) return;
        say.accept(BuildOutput.STEP_INDENT + "Masked " + String.join(", ", gained.get()) + " on " + name
                + ", which it gained after it was airgapped.");
    }
}
