package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.JacksonTrees;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The halves of {@link InstancePrep} that {@code isx shell}/{@code isx run} and the TUI's shell
 * share (#907). They are tested here rather than per front end because both call them: whatever
 * one of them reaches, the other does too.
 */
@ExtendWith(IsolatedHome.class)
class InstancePrepTest {

    /** Unique: readyForUse creates the host zmx symlink, which IsolatedHome does not redirect. */
    private static final String NAME = "prep-" + java.util.UUID.randomUUID();

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
}
