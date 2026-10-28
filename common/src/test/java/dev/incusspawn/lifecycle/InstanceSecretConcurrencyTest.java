package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.InstanceSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * Two isx processes acting on one instance in the same boot -- two {@code isx shell}s, a shell and
 * the TUI, MCP's {@code start_instance} -- must leave the guest holding the secret whose hash the
 * host records (#1065). Each rotates the hash and then delivers the secret, so without a lock over
 * the pair the one delivering last can be the one whose hash was overwritten, and the boot is
 * stamped as holding its secret, so nothing repairs it before the next isx start.
 *
 * <p>Each test forces the interleaving with a latch that times out: once rotate and deliver are
 * serialized, the forced order cannot happen, and the second process waits for the first instead.
 */
@ExtendWith(TempHome.class)
class InstanceSecretConcurrencyTest {

    private static final String NAME = "box";
    private static final String BOOTED = "2026-10-05T07:30:00.987654321Z";
    /** How long the first process holds its delivery back for the second; only waited out once fixed. */
    private static final long HOLD_BACK_MS = 500;

    /** What the guest was handed: the secret it holds, and how many deliveries it took. */
    private static final class Guest {
        final AtomicReference<String> secret = new AtomicReference<>();
        final AtomicInteger deliveries = new AtomicInteger();

        void take(Map<String, String> env) {
            secret.set(env.get(InstanceSecret.DELIVERY_ENV));
            deliveries.incrementAndGet();
        }
    }

    /**
     * One isx process: its own client over the shared daemon, whose guest takes what it is
     * handed. {@code afterStart} runs once its start returns, {@code beforeDelivery} before it
     * hands the guest a secret.
     */
    private static IncusClient process(FakeIncusDaemon daemon, Guest guest, Runnable afterStart, Runnable beforeDelivery) {
        var incus = spy(daemon.client());
        doAnswer(call -> {
            call.callRealMethod();
            afterStart.run();
            return null;
        }).when(incus).start(NAME);
        doAnswer(call -> {
            beforeDelivery.run();
            guest.take(call.getArgument(3));
            return null;
        }).when(incus).waitForReady(eq(NAME), any(MachineType.class), any(), anyMap());
        doAnswer(call -> {
            beforeDelivery.run();
            guest.take(call.getArgument(1));
            return new IncusClient.ExecResult(0, "", "");
        }).when(incus).shellExec(eq(NAME), anyMap(), any(String[].class));
        return incus;
    }

    private static final Runnable NOTHING = () -> {};

    private static Runnable signalThenHoldBack(CountDownLatch signal, CountDownLatch until) {
        return () -> {
            signal.countDown();
            try {
                until.await(HOLD_BACK_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static void runBoth(ThrowingRunnable first, ThrowingRunnable second, CountDownLatch secondDone) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { first.run(); return null; });
            var b = pool.submit(() -> {
                try {
                    second.run();
                } finally {
                    secondDone.countDown();
                }
                return null;
            });
            b.get(30, TimeUnit.SECONDS);
            a.get(30, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertGuestHoldsTheRecordedSecret(FakeIncusDaemon daemon, Guest guest) {
        var config = daemon.instance(NAME).path("config");
        assertNotNull(guest.secret.get(), "the guest was given no secret");
        assertEquals(config.pathText(Metadata.INSTANCE_SECRET_SHA256, ""), InstanceSecret.sha256(guest.secret.get()),
                "the guest holds one secret and the host records another, for the rest of this boot");
    }

    /** A shell reaching a running container while another isx is still waiting for the start it made. */
    @Test
    void aShellDuringAnotherProcesssStartLeavesTheStartsSecretInPlace() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        var guest = new Guest();
        var started = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);
        var a = process(daemon, guest, signalThenHoldBack(started, secondDone), NOTHING);
        var b = process(daemon, guest, NOTHING, NOTHING);

        runBoth(() -> InstanceLifecycle.startForUse(a, NAME, daemon.instance(NAME), MachineType.CONTAINER, RecordingOutput.QUIET),
                () -> {
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    InstanceLifecycle.ensureReady(b, NAME, daemon.instance(NAME), MachineType.CONTAINER, RecordingOutput.QUIET);
                }, secondDone);

        assertGuestHoldsTheRecordedSecret(daemon, guest);
    }

    /**
     * Two shells reaching a container rebooted outside isx, both from a listing taken before either
     * acted. The second waits for the first, then finds the boot holding its secret instead of
     * rotating the hash again behind a delivery it did not see.
     */
    @Test
    void twoShellsAfterARebootGiveItOneSecret() throws Exception {
        var config = new HashMap<String, String>();
        config.put(Metadata.INSTANCE_SECRET_SHA256, InstanceSecret.sha256(InstanceSecret.generate()));
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running", config).lastUsedAt(NAME, BOOTED);
        var listing = daemon.instance(NAME);
        var guest = new Guest();
        var rotated = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);
        var a = process(daemon, guest, NOTHING, signalThenHoldBack(rotated, secondDone));
        var b = process(daemon, guest, NOTHING, NOTHING);

        runBoth(() -> InstanceLifecycle.ensureReady(a, NAME, listing, MachineType.CONTAINER, RecordingOutput.QUIET),
                () -> {
                    assertTrue(rotated.await(10, TimeUnit.SECONDS));
                    InstanceLifecycle.ensureReady(b, NAME, listing, MachineType.CONTAINER, RecordingOutput.QUIET);
                }, secondDone);

        assertGuestHoldsTheRecordedSecret(daemon, guest);
        assertEquals(1, guest.deliveries.get(), "the second finds the boot already holding the first's secret");
    }

    /**
     * Two shells on a stopped container, both from a listing that says it is stopped: the second
     * must not rotate the hash of a box the first is starting, nor fail on a start already made.
     */
    @Test
    void twoShellsOnAStoppedContainerBothOpenOnOneSecret() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        var listing = daemon.instance(NAME);
        var guest = new Guest();
        var started = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);
        var a = process(daemon, guest, NOTHING, signalThenHoldBack(started, secondDone));
        var b = process(daemon, guest, NOTHING, NOTHING);

        runBoth(() -> InstanceLifecycle.ensureReady(a, NAME, listing, MachineType.CONTAINER, RecordingOutput.QUIET),
                () -> {
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    InstanceLifecycle.ensureReady(b, NAME, listing, MachineType.CONTAINER, RecordingOutput.QUIET);
                }, secondDone);

        assertGuestHoldsTheRecordedSecret(daemon, guest);
    }

    /** The holder's guest never answered, so it delivered nothing: the waiter finds the box running without a secret and gives it one. */
    @Test
    void aWaiterGivesTheSecretAFailedHolderDidNot() throws Exception {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        var listing = daemon.instance(NAME);
        var guest = new Guest();
        var started = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);
        var a = process(daemon, guest, NOTHING, NOTHING);
        doAnswer(call -> {
            signalThenHoldBack(started, secondDone).run();
            throw new IncusException("box did not answer");
        }).when(a).waitForReady(eq(NAME), any(MachineType.class), any(), anyMap());
        var b = process(daemon, guest, NOTHING, NOTHING);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> InstanceLifecycle.ensureReady(a, NAME, listing, MachineType.CONTAINER, RecordingOutput.QUIET));
            var second = pool.submit(() -> {
                try {
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    InstanceLifecycle.ensureReady(b, NAME, listing, MachineType.CONTAINER, RecordingOutput.QUIET);
                } finally {
                    secondDone.countDown();
                }
                return null;
            });
            second.get(30, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> first.get(30, TimeUnit.SECONDS));
        }

        assertGuestHoldsTheRecordedSecret(daemon, guest);
    }

    /**
     * A listing read before another isx made a whole start, handed on after it let go: the lock
     * was free, so nothing waited. The second start fails on the running box, whose hash it has
     * replaced, so it gives the box a secret and its shell opens. A VM most of all: its probe only
     * asks whether a secret is there, so nothing later would tell.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(MachineType.class)
    void aStartFromAListingOlderThanAnotherStartGivesTheBoxASecret(MachineType type) {
        var daemon = new FakeIncusDaemon().instance(NAME, type == MachineType.VM ? "virtual-machine" : "container",
                "Stopped", Map.of()).ipFiltering(NAME, "true");
        var listing = daemon.instance(NAME);
        var guest = new Guest();
        var a = process(daemon, guest, NOTHING, NOTHING);
        var b = process(daemon, guest, NOTHING, NOTHING);

        InstanceLifecycle.ensureReady(a, NAME, listing, type, RecordingOutput.QUIET);
        InstanceLifecycle.ensureReady(b, NAME, listing, type, RecordingOutput.QUIET);

        assertGuestHoldsTheRecordedSecret(daemon, guest);
        assertEquals(2, guest.deliveries.get(), "the second start's hash went to the guest too");
    }

    /** Incus started it but its answer was lost: still waited for, given its secret and recorded. */
    @Test
    void aStartWhoseAnswerWasLostStillWaitsAndDelivers() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        var guest = new Guest();
        var incus = process(daemon, guest, () -> { throw new IncusException("Operation wait failed"); }, NOTHING);

        InstanceLifecycle.ensureReady(incus, NAME, daemon.instance(NAME), MachineType.CONTAINER, RecordingOutput.QUIET);

        assertGuestHoldsTheRecordedSecret(daemon, guest);
        assertEquals(1, guest.deliveries.get(), "delivered by the readiness probe, with no second rotation");
        var bootedAt = daemon.instance(NAME).pathText("last_used_at", "");
        assertEquals(InstanceLifecycle.secretBootStamp(bootedAt, daemon.instance(NAME).path("config").pathText(Metadata.INSTANCE_PROOF_ID, "")),
                daemon.instance(NAME).path("config").pathText(Metadata.INSTANCE_SECRET_BOOT, ""));
    }

    @Test
    void aStartThatLeavesItStoppedStillFails() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        var incus = process(daemon, new Guest(), NOTHING, NOTHING);
        doThrow(new IncusException("refused")).when(incus).start(NAME);

        var e = assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(
                incus, NAME, daemon.instance(NAME), MachineType.CONTAINER, RecordingOutput.QUIET));
        assertEquals("refused", e.getMessage());
    }
}
