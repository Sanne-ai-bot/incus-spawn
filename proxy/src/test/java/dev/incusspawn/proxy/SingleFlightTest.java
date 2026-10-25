package dev.incusspawn.proxy;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleFlightTest {

    @Test
    void aCallerWhileWorkIsInFlightJoinsIt() {
        var flight = new SingleFlight<String, String>();
        var work = Promise.<String>promise();
        var started = new AtomicInteger();

        var first = flight.join("k", () -> {
            started.incrementAndGet();
            return work.future();
        });
        var second = flight.join("k", () -> {
            started.incrementAndGet();
            return Future.succeededFuture("other");
        });
        assertEquals(1, flight.joined());
        work.complete("answer");

        assertEquals(1, started.get());
        assertEquals("answer", first.result());
        assertEquals("answer", second.result());
        assertTrue(flight.isEmpty());
    }

    @Test
    void theKeyIsReleasedBeforeAnyCallerHearsOfTheResult() {
        var flight = new SingleFlight<String, String>();
        var work = Promise.<String>promise();
        var sizeSeen = new AtomicInteger(-1);
        flight.join("k", work::future).onComplete(ar -> sizeSeen.set(flight.size()));

        work.complete("answer");

        assertEquals(0, sizeSeen.get(), "a caller answered then would start its own work");
    }

    @Test
    void aCallerAfterTheWorkCompletedStartsItsOwn() {
        var flight = new SingleFlight<String, String>();
        assertEquals("one", flight.join("k", () -> Future.succeededFuture("one")).result());
        assertEquals("two", flight.join("k", () -> Future.succeededFuture("two")).result());
        assertEquals(0, flight.joined());
    }

    @Test
    void otherKeysAreNotJoined() {
        var flight = new SingleFlight<String, String>();
        var held = Promise.<String>promise();
        flight.join("a", held::future);
        assertEquals("b", flight.join("b", () -> Future.succeededFuture("b")).result());
        assertEquals(1, flight.size());
        held.complete("a");
    }

    @Test
    void workThatThrowsFailsItsCallersAndReleasesTheKey() {
        var flight = new SingleFlight<String, String>();
        var failed = flight.join("k", () -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(failed.failed());
        assertEquals("boom", failed.cause().getMessage());
        assertTrue(flight.isEmpty());
    }

    @Test
    void workThatThrowsAnErrorReleasesTheKeyToo() {
        var flight = new SingleFlight<String, String>();
        var failed = flight.join("k", () -> {
            throw new StackOverflowError("deep");
        });
        assertTrue(failed.failed());
        assertTrue(flight.isEmpty(), "every later caller would otherwise hang on this key");
        assertEquals("next", flight.join("k", () -> Future.succeededFuture("next")).result());
    }
}
