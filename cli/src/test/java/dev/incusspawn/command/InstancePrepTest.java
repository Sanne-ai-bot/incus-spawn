package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.JacksonTrees;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.ZmxSocketForward;
import dev.incusspawn.tool.GhSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The halves of {@link InstancePrep} that {@code isx shell}/{@code isx run} and the TUI's shell
 * share (#907). They are tested here rather than per front end because both call them: whatever
 * one of them reaches, the other does too.
 */
@ExtendWith(IsolatedHome.class)
class InstancePrepTest {

    /**
     * Unique, and cleaned up after each test: readyForUse creates the host zmx symlink, in a
     * directory IsolatedHome does not redirect.
     */
    private static final String NAME = "prep-" + UUID.randomUUID();

    @AfterEach
    void removeZmxSymlink() {
        ZmxSocketForward.cleanup(NAME);
    }

    private static final String WITH_TOKEN = """
            github:
              accounts:
                me:
                  token: "ghp_userA"
                  email: "me@example.com"
              default: me
            """;

    /**
     * An instance whose account changed while it was stopped (TUI {@code a}, {@code isx account
     * set}, {@code isx branch --no-start --account}) is reconciled by its next use; the TUI's
     * shell used to skip that, so it pushed as the new account and committed as the old one.
     * Here: an instance from an older template, unverified, which the reconcile asks the guest
     * about once and marks.
     */
    @Test
    void readyForUseReconcilesTheAccountIdentities() throws Exception {
        IsolatedHome.seed(WITH_TOKEN);
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running", Map.of()).servesExecs();
        var warnings = new ArrayList<String>();

        InstancePrep.readyForUse(daemon.client(), NAME, JacksonTrees.fromJsonNode(daemon.instance(NAME)),
                MachineType.CONTAINER, msg -> { }, warnings::add);

        assertEquals(List.of(), warnings);
        assertEquals("true", daemon.instance(NAME).path("config").path(Metadata.ACCOUNT_IDENTITY_VERIFIED).asText(),
                "the guest was never asked:\n" + String.join("\n", daemon.requests()));
    }

    /**
     * IP spoofing protection is re-armed before the start the CA repair makes (#905): the proxy
     * picks a credential account by source address, so a stopped instance the fallback left
     * without it would otherwise come up able to spend a neighbour's account.
     * {@code IpFilteringRearmTest} pins the step itself; this pins that both shells reach it.
     */
    @Test
    void repairBeforeUseReArmsSpoofingProtectionBeforeStartingAStoppedInstance() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "false");

        // FakeIncusDaemon serves no exec, so the readiness wait after the start can only time
        // out; what matters here is what reached Incus before it.
        assertThrows(IncusException.class, () -> InstancePrep.repairBeforeUse(daemon.clientWithShortReadyWait(),
                NAME, MachineType.CONTAINER, notices(w -> fail(w))));

        assertEquals("true", filtering(daemon));
        var requests = daemon.requests();
        int write = requests.lastIndexOf("PATCH /1.0/instances/" + NAME);
        int start = requests.indexOf("PUT /1.0/instances/" + NAME + "/state");
        assertTrue(write >= 0 && start > write, "must be on before the start that applies it:\n"
                + String.join("\n", requests));
        assertEquals(List.of(NAME + " start"), daemon.stateActions());
    }

    /** The TUI owns the terminal while it repairs: a refused re-arm goes to its sink, not stderr. */
    @Test
    void aRefusedReArmIsReportedToTheStartSink() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "false")
                .refuseWritesContaining("ipv4_filtering");
        var warnings = new ArrayList<String>();

        assertThrows(IncusException.class, () -> InstancePrep.repairBeforeUse(daemon.clientWithShortReadyWait(),
                NAME, MachineType.CONTAINER, notices(warnings::add)));

        assertEquals("false", filtering(daemon));
        assertTrue(String.join("\n", warnings).contains("impersonate each other's credential accounts"),
                warnings.toString());
    }

    /**
     * A running, healthy instance, as most shells find it: what both front ends pay before the
     * shell. Each of these reads the same instance (#897), so fewer is an improvement -- lower
     * the budget; more is a latency regression on every shell.
     */
    @Test
    void aHealthyRunningInstanceCostsNoMoreThanBefore() throws Exception {
        // An account configured and the instance's identity current: the reconcile decides from
        // the caller's read that nothing is stale
        var config = IsolatedHome.seed(WITH_TOKEN);
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running", Map.of(
                        Metadata.ACCOUNT_IDENTITY_VERIFIED, "true",
                        Metadata.accountIdentityKey("github"), new GhSetup().bakedAccountIdentity(config, "me")))
                .ipFiltering(NAME, "true");
        var incus = daemon.client();

        InstancePrep.repairBeforeUse(incus, NAME, MachineType.CONTAINER, notices(w -> fail(w)));
        InstancePrep.readyForUse(incus, NAME, JacksonTrees.fromJsonNode(daemon.instance(NAME)),
                MachineType.CONTAINER, msg -> { }, w -> fail(w));

        var requests = daemon.requests();
        // Four reads of the instance, then resolv.conf's bridge lookup and exec; the reconcile
        // works from the caller's read, so with nothing stale it costs nothing
        assertEquals(List.of("GET /1.0/instances/" + NAME, "GET /1.0/instances/" + NAME,
                        "GET /1.0/instances/" + NAME, "GET /1.0/instances/" + NAME, "GET /1.0/networks/incusbr0",
                        "POST /1.0/instances/" + NAME + "/exec"), requests,
                () -> String.join("\n", requests));
    }

    private static InstancePrep.Notices notices(Consumer<String> startWarn) {
        return new InstancePrep.Notices(
                new dev.incusspawn.incus.StaticIpAllocator.Output(msg -> { }, w -> fail(w)), startWarn, (title, detail) -> { });
    }

    private static String filtering(FakeIncusDaemon daemon) {
        return daemon.instance(NAME).path("expanded_devices").path("eth0")
                .path("security.ipv4_filtering").asText();
    }
}
