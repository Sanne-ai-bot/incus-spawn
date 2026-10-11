package dev.incusspawn.proxy;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiActivityTest {

    @Test
    void onlyMessageCreatesAreModelCalls() {
        assertTrue(ApiActivity.isModelCall("/v1/messages"));
        assertTrue(ApiActivity.isModelCall("/v1/projects/p/locations/global/publishers/anthropic/models/claude-opus-5-5:streamRawPredict"));
        assertTrue(ApiActivity.isModelCall("/v1/projects/p/locations/global/publishers/anthropic/models/claude-opus-5-5:rawPredict"));
        assertFalse(ApiActivity.isModelCall("/v1/messages/count_tokens"));
        assertFalse(ApiActivity.isModelCall("/v1/messages/batches"));
        assertFalse(ApiActivity.isModelCall("/v1/projects/p/locations/global/publishers/anthropic/models/count-tokens:rawPredict"));
        assertFalse(ApiActivity.isModelCall("/api/event_logging/batch"));
        assertFalse(ApiActivity.isModelCall(null));
    }

    @Test
    void aCallIsInFlightUntilItEndsOnceHoweverItEnds() {
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        var first = activity.begin("w", "", 0);
        now.set(2_000);
        activity.begin("w", "", 0);

        var w = activity.snapshot(Map.of("w", ""), 0).of("w");
        assertEquals(2, w.requests());
        assertEquals(2, w.inFlight());
        assertEquals(Instant.ofEpochMilli(2_000), w.lastRequestAt());
        assertNull(w.lastResponseAt(), "nothing answered yet");

        now.set(3_000);
        first.end();
        first.end(); // the response's end and its connection's close both report it
        w = activity.snapshot(Map.of("w", ""), 0).of("w");
        assertEquals(1, w.inFlight());
        assertEquals(Instant.ofEpochMilli(3_000), w.lastResponseAt());
        assertEquals(Instant.ofEpochMilli(1_000), w.countingSince(), "from the first call");
    }

    @Test
    void anInstanceNoLongerKnownIsForgottenOnceIdle() {
        var activity = new ApiActivity();
        activity.begin("gone", "", 0).end();
        activity.begin("kept", "", 0).end();
        activity.begin("busy", "", 0);

        var instances = activity.snapshot(Map.of("kept", ""), 0).instances();

        assertTrue(instances.containsKey("kept"));
        assertFalse(instances.containsKey("gone"));
        assertEquals(1, instances.get("busy").inFlight(), "a call in flight keeps its counters until it ends");
    }

    @Test
    void aKnownInstanceHasCountersFromTheFirstReadSoNoReadLacksAStart() {
        // A first read without counting_since must never be subtracted as zero: counters made
        // after it could be dropped and made again before the second, undercounting unseen
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);

        var first = activity.snapshot(Map.of("w", ""), 0).of("w");
        now.set(2_000);
        activity.begin("w", "", 0).end();
        var second = activity.snapshot(Map.of("w", ""), 0).of("w");

        assertEquals(Instant.ofEpochMilli(1_000), first.countingSince());
        assertEquals(0, first.requests());
        assertEquals(first.countingSince(), second.countingSince(), "the same start: subtractable");
        assertEquals(1, second.requests() - first.requests());
        assertNull(activity.snapshot(Map.of("w", ""), 0).of("unknown").countingSince(), "nothing for one it does not know");
    }

    @Test
    void countsDroppedBetweenTwoReadsAreNeverSubtractedAsIfTheyWereNot() {
        // The registry can miss a live instance for a moment (an address two instances claim, a
        // listing that failed to parse): its counters are dropped, and that must show (#1052)
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        var call = activity.begin("w", "", 0);
        call.respond(200, "application/json", null);
        call.accept(bytes("{\"usage\":{\"output_tokens\":500}}"));
        call.end();
        var before = activity.snapshot(Map.of("w", ""), 0).of("w");

        now.set(2_000);
        assertFalse(activity.snapshot(Map.of(), 0).instances().containsKey("w"), "dropped while unlisted");
        now.set(3_000);
        var again = activity.begin("w", "", 0);
        again.respond(200, "application/json", null);
        again.accept(bytes("{\"usage\":{\"output_tokens\":20}}"));
        again.end();
        var after = activity.snapshot(Map.of("w", ""), 0).of("w");

        assertEquals(500, before.outputTokens());
        assertEquals(20, after.outputTokens(), "a subtraction would say -480");
        assertNotEquals(before.countingSince(), after.countingSince(),
                "so the client sees the counts started afresh and does not subtract");
        assertEquals(Instant.ofEpochMilli(3_000), after.countingSince());
    }

    @Test
    void aNameBranchedAgainCountsAfreshWhicheverSideSeesItFirst() {
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        activity.begin("box", "t1", 1).end();

        now.set(2_000);
        var read = activity.snapshot(Map.of("box", "t2"), 2).of("box");
        assertEquals(0, read.requests(), "a read naming the new one does not report the old one's call");
        assertEquals(Instant.ofEpochMilli(2_000), read.countingSince());

        now.set(3_000);
        activity.begin("box", "t3", 3).end();
        var after = activity.snapshot(Map.of("box", "t3"), 3).of("box");
        assertEquals(1, after.requests(), "nor does a call from the next one carry on from it");
        assertEquals(Instant.ofEpochMilli(3_000), after.countingSince());
    }

    @Test
    void aViewFromBeforeTheNameWasBranchedAgainNeverResetsTheNewInstance() {
        // A call identified, or a read listed, before the refresh that saw the new instance. The
        // views decide, not created_at: the old one's is the later here, as a rename can make it
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        var old = "2026-10-07T12:00:00Z";
        var reborn = "2026-10-07T11:00:00.5+00:00";
        var streaming = activity.begin("box", reborn, 2);

        now.set(2_000);
        activity.begin("box", old, 1).end();
        var stale = activity.snapshot(Map.of("box", old), 1).of("box");
        var read = activity.snapshot(Map.of("box", reborn), 2).of("box");

        assertEquals(stale, read, "the read from the older snapshot reports the newer counters too");
        assertEquals(1, read.requests(), "the destroyed instance's late call is not the new one's");
        assertEquals(1, read.inFlight(), "and the new one's call in flight is still counted");
        assertEquals(Instant.ofEpochMilli(1_000), read.countingSince());
        streaming.end();
    }

    @Test
    void anOlderInstanceRenamedOntoTheNameCountsAsANewOne() {
        // isx's rename keeps the instance's own created_at, which may predate the destroyed one's
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        activity.begin("w", "2026-10-07T12:00:00Z", 1).end();

        now.set(2_000);
        var renamed = "2026-10-01T09:00:00Z";
        var working = activity.begin("w", renamed, 2);
        var read = activity.snapshot(Map.of("w", renamed), 2).of("w");

        assertEquals(1, read.requests(), "its own call, not the destroyed one's");
        assertEquals(1, read.inFlight(), "a working agent is never reported idle");
        assertEquals(Instant.ofEpochMilli(2_000), read.countingSince());
        working.end();
    }

    @Test
    void aReadFromAListingBeforeTheInstanceExistedDoesNotDropItsCounts() {
        // The read took the registry's view before the refresh that listed a new branch
        var activity = new ApiActivity();
        activity.begin("w", "t", 5).end();

        assertTrue(activity.snapshot(Map.of(), 4).instances().containsKey("w"));
        assertFalse(activity.snapshot(Map.of(), 5).instances().containsKey("w"), "a listing that saw it gone drops them");
    }

    @Test
    void anOversizedEventIsSkippedAndTheNextOneStillRead() {
        var tap = new ApiActivity.UsageTap(true);
        tap.accept(bytes("data: {\"usage\":{\"input_tokens\":" + "9".repeat(ApiActivity.UsageTap.MAX_LINE)));
        tap.accept(bytes("}}\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n"));

        var usage = tap.finish();

        assertEquals(0, usage.input);
        assertEquals(5, usage.output);
    }

    /** A body cut short is not counted: a truncated number would read as a smaller one. */
    @Test
    void aTruncatedResponseCountsNoUsage() {
        var json = new ApiActivity.UsageTap(false);
        json.accept(bytes("{\"type\":\"message\",\"usage\":{\"input_tokens\":12"));
        assertNull(json.finish(), "{\"input_tokens\":1234} cut after 12 must not count 12");

        var stream = new ApiActivity.UsageTap(true);
        stream.accept(bytes("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":12\n"));
        stream.accept(bytes("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n"));
        assertEquals(5, stream.finish().output, "the cut event is skipped, the next one read");
    }

    @Test
    void aResponseWithoutUsageReportsNone() {
        var tap = new ApiActivity.UsageTap(true);
        tap.accept(bytes("event: ping\ndata: {\"type\":\"ping\"}\n\ndata: not json, \"usage\"\n"));
        assertNull(tap.finish());

        var json = new ApiActivity.UsageTap(false);
        json.accept(bytes("{\"usage\":{\"input_tokens\":"));
        json.accept(bytes("4,\"output_tokens\":2}}"));
        assertEquals(4, json.finish().input);
    }

    @Test
    void anOversizedJsonBodyIsNotRead() {
        var json = new ApiActivity.UsageTap(false);
        json.accept(bytes("{\"usage\":{\"input_tokens\":4},\"pad\":\""));
        json.accept(Buffer.buffer(new byte[ApiActivity.UsageTap.MAX_BODY]));
        json.accept(bytes("\"}"));
        assertNull(json.finish());
    }

    @Test
    void whatTheProxyServesIsWhatTheCliReads() {
        var now = new AtomicLong(5_000);
        var activity = new ApiActivity(now::get);
        var call = activity.begin("w", "", 0);
        call.respond(200, "application/json", null);
        call.accept(bytes("{\"usage\":{\"input_tokens\":1,\"output_tokens\":2,"
                + "\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":4}}"));
        now.set(6_000);
        call.end();
        activity.begin("w", "", 0);

        var read = ProxyActivity.parse(activity.snapshot(Map.of("w", ""), 0).toJson());

        assertEquals(new ProxyActivity.Instance(Instant.ofEpochMilli(5_000), 2, 1, Instant.ofEpochMilli(6_000),
                Instant.ofEpochMilli(6_000), 1, 2, 3, 4), read.of("w"));
        assertEquals(ProxyActivity.Instance.NONE, read.of("never-called"));
    }

    @Test
    void aRequestCountsForItsInstanceAloneAndByDomain() {
        // #935: all traffic, not only model calls
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        var flow = activity.flow("a", "", 0, "github.com");
        now.set(2_000);
        end(flow, 10, 200);
        activity.flow("a", "", 0, "api.anthropic.com");

        var read = activity.snapshot(Map.of("a", "", "b", ""), 0);
        var a = read.of("a").traffic();
        assertEquals(new ProxyActivity.Counts(2, 1, Instant.ofEpochMilli(2_000), 10, 200), a.total());
        assertEquals(new ProxyActivity.Counts(1, 0, Instant.ofEpochMilli(2_000), 10, 200), a.byDomain().get("github.com"));
        assertEquals(new ProxyActivity.Counts(1, 1, Instant.ofEpochMilli(2_000), 0, 0), a.byDomain().get("api.anthropic.com"));
        assertEquals(0, read.of("a").requests(), "a request that is not a model call is no model call");
        assertEquals(ProxyActivity.Traffic.NONE, read.of("b").traffic(), "nothing of b's");
        assertEquals(ProxyActivity.Traffic.NONE, read.unattributed());
    }

    @Test
    void aCallerThatIsNoKnownInstanceCountsAsUnattributed() {
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        now.set(2_000);
        end(activity.flow(null, null, 0, "registry.npmjs.org"), 0, 5);

        var read = activity.snapshot(Map.of(), 0);
        assertTrue(read.instances().isEmpty(), read.toString());
        assertEquals(Instant.ofEpochMilli(1_000), read.unattributedSince(), "counted since the proxy started");
        assertEquals(new ProxyActivity.Counts(1, 0, Instant.ofEpochMilli(2_000), 0, 5), read.unattributed().total());
        assertEquals(Map.of("registry.npmjs.org", new ProxyActivity.Counts(1, 0, Instant.ofEpochMilli(2_000), 0, 5)),
                read.unattributed().byDomain());
    }

    @Test
    void aModelCallEndsWithItsRequestOnOneReadingOfTheClock() {
        // #1281: the response's handlers end the flow and the call one after the other
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::incrementAndGet);
        var flow = activity.flow("w", "", 0, "api.anthropic.com");
        var call = activity.begin("w", "", 0);
        flow.carries(call);

        flow.end();
        call.end();
        var w = activity.snapshot(Map.of("w", ""), 0).of("w");
        assertEquals(0, w.inFlight());
        assertEquals(w.traffic().byDomain().get("api.anthropic.com").lastActivityAt(), w.lastResponseAt());

        // A call that begins once its request's connection is gone ends at once
        var gone = activity.flow("w", "", 0, "api.anthropic.com");
        gone.end();
        gone.carries(activity.begin("w", "", 0));
        w = activity.snapshot(Map.of("w", ""), 0).of("w");
        assertEquals(0, w.inFlight());
        assertEquals(w.traffic().byDomain().get("api.anthropic.com").lastActivityAt(), w.lastResponseAt());
    }

    @Test
    void aFlowIsInFlightUntilItEndsOnceHoweverItEnds() {
        var activity = new ApiActivity();
        var socket = activity.flow("w", "", 0, "github.com");
        socket.addRequestBytes(3);
        socket.addResponseBytes(4);
        assertEquals(1, activity.snapshot(Map.of("w", ""), 0).of("w").traffic().total().inFlight());

        socket.end();
        end(socket, 100, 100); // the socket's close and its connection's both report it
        var total = activity.snapshot(Map.of("w", ""), 0).of("w").traffic().total();
        assertEquals(0, total.inFlight());
        assertEquals(3, total.requestBytes());
        assertEquals(4, total.responseBytes());
    }

    @Test
    void anInstanceNoLongerKnownKeepsItsTrafficCountersOnlyWhileAFlowIsOpen() {
        var activity = new ApiActivity();
        var open = activity.flow("gone", "", 0, "github.com");

        assertTrue(activity.snapshot(Map.of(), 0).instances().containsKey("gone"), "an open WebSocket keeps them");
        open.end();
        assertFalse(activity.snapshot(Map.of(), 0).instances().containsKey("gone"));
    }

    @Test
    void aSubdomainCountsUnderTheInterceptedDomainItIsRelayedFor() {
        var intercepted = java.util.Set.of("quay.io", "github.com", "api.github.com");
        assertEquals("quay.io", ApiActivity.trafficDomain("cdn01.quay.io", intercepted));
        assertEquals("api.github.com", ApiActivity.trafficDomain("uploads.api.github.com", intercepted), "the closest");
        assertEquals("github.com", ApiActivity.trafficDomain("github.com", intercepted));
        // The Host header is the guest's to write: names it makes up must not grow the map
        assertEquals(ProxyActivity.Traffic.OTHER_DOMAIN, ApiActivity.trafficDomain("made-up-1.example", intercepted));
        assertEquals(ProxyActivity.Traffic.OTHER_DOMAIN, ApiActivity.trafficDomain("evilquay.io", intercepted));
        assertEquals(ProxyActivity.Traffic.OTHER_DOMAIN, ApiActivity.trafficDomain(null, intercepted));
    }

    @Test
    void trafficSurvivesTheJsonTheCliReads() {
        var now = new AtomicLong(5_000);
        var activity = new ApiActivity(now::get);
        end(activity.flow("w", "", 0, "github.com"), 1, 2);
        activity.flow(null, null, 0, ProxyActivity.Traffic.OTHER_DOMAIN);

        var served = activity.snapshot(Map.of("w", ""), 0);
        var read = ProxyActivity.parse(served.toJson());

        assertEquals(served.of("w").traffic(), read.of("w").traffic());
        assertEquals(served.unattributed(), read.unattributed());
        assertEquals(Instant.ofEpochMilli(5_000), read.unattributedSince());
        assertEquals(ProxyActivity.Traffic.NONE, ProxyActivity.parse("{\"instances\":{\"w\":{\"requests\":1}}}")
                .of("w").traffic(), "a proxy from before #935 reports none");
    }

    private static void end(ApiActivity.Flow flow, long requestBytes, long responseBytes) {
        flow.addRequestBytes(requestBytes);
        flow.addResponseBytes(responseBytes);
        flow.end();
    }

    private static Buffer bytes(String s) {
        return Buffer.buffer(s);
    }
}
