package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The proxy's {@code /activity} (#898): each instance's Claude model calls -- requests made and
 * in flight, when the last started and ended, and the tokens their responses reported -- and all
 * of its traffic through the proxy, by domain (#935). Traffic from no instance the proxy knows
 * (the host, or an address it has not listed) is counted apart, as {@code unattributed}, from
 * {@code unattributedSince}. Only the host is answered. Fields are only ever added.
 */
public record ProxyActivity(Map<String, Instance> instances, Instant unattributedSince, Traffic unattributed) {

    public ProxyActivity(Map<String, Instance> instances) {
        this(instances, null, Traffic.NONE);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One instance's counts, all counted from {@code countingSince}: when the proxy created them,
     * on the first read or call after it learned of the instance, or again after it dropped them.
     * Two reads subtract only with the same {@code countingSince}. Times are null until there is
     * one; {@link #NONE}, an instance the proxy does not know, has none at all.
     */
    public record Instance(Instant countingSince, long requests, long inFlight,
                           Instant lastRequestAt, Instant lastResponseAt,
                           long inputTokens, long outputTokens,
                           long cacheReadInputTokens, long cacheCreationInputTokens,
                           Traffic traffic) {
        public static final Instance NONE = new Instance(null, 0, 0, null, null, 0, 0, 0, 0);

        /** Model calls only, with no traffic counted. */
        public Instance(Instant countingSince, long requests, long inFlight,
                        Instant lastRequestAt, Instant lastResponseAt,
                        long inputTokens, long outputTokens,
                        long cacheReadInputTokens, long cacheCreationInputTokens) {
            this(countingSince, requests, inFlight, lastRequestAt, lastResponseAt,
                    inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, Traffic.NONE);
        }
    }

    /**
     * Requests (a WebSocket counts as one, in flight while open) and their body bytes, model
     * calls included. Bytes and {@code lastActivityAt} move when a request starts and ends, so
     * a long download or an open WebSocket shows as {@code inFlight}, not as bytes.
     */
    public record Counts(long requests, long inFlight, Instant lastActivityAt, long requestBytes, long responseBytes) {
        public static final Counts NONE = new Counts(0, 0, null, 0, 0);
    }

    /**
     * All of one caller's requests, and the same by domain: an intercepted domain, a
     * subdomain counted under the intercepted domain it is relayed for, or {@link #OTHER_DOMAIN}.
     */
    public record Traffic(Counts total, Map<String, Counts> byDomain) {
        public static final Traffic NONE = new Traffic(Counts.NONE, Map.of());
        /** Where a request to a name the proxy does not intercept is counted; never a DNS name. */
        public static final String OTHER_DOMAIN = "(other)";
    }

    /** No answer worth reading: the proxy is down, or older than {@code /activity}. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** The running proxy predates {@code /activity}: no retry helps until it is restarted. */
    public static final class Outdated extends Unavailable {
        public Outdated(String message) {
            super(message);
        }
    }

    /** What the proxy counted for {@code instance}: {@link Instance#NONE} for one it does not know. */
    public Instance of(String instance) {
        return instances.getOrDefault(instance, Instance.NONE);
    }

    /** Asks the proxy whose health endpoint is at {@code address} ({@link ProxyHealthCheck#healthAddress}). */
    public static ProxyActivity fetch(String address) {
        return fetch(address, ProxyConfig.DEFAULT_HEALTH_PORT);
    }

    static ProxyActivity fetch(String address, int port) {
        ProxyHealthCheck.Answer answer;
        try {
            answer = ProxyHealthCheck.get(address, port, "/activity", 2000);
        } catch (Exception e) {
            throw new Unavailable("the isx proxy is not answering (" + e.getMessage() + ")");
        }
        if (answer.status() == 404) throw new Outdated("the running isx proxy does not report activity; it predates this isx");
        if (answer.status() != 200) throw new Unavailable("the isx proxy answered HTTP " + answer.status());
        return parse(answer.body());
    }

    /** The JSON the proxy serves; {@link #parse} reads it back, so the names live only here. */
    public String toJson() {
        var root = JSON.createObjectNode();
        var all = root.putObject("instances");
        instances.forEach((name, c) -> {
            var n = all.putObject(name);
            n.put("counting_since", c.countingSince().toEpochMilli());
            n.put("requests", c.requests());
            n.put("in_flight", c.inFlight());
            if (c.lastRequestAt() != null) n.put("last_request_at", c.lastRequestAt().toEpochMilli());
            if (c.lastResponseAt() != null) n.put("last_response_at", c.lastResponseAt().toEpochMilli());
            n.put("input_tokens", c.inputTokens());
            n.put("output_tokens", c.outputTokens());
            n.put("cache_read_input_tokens", c.cacheReadInputTokens());
            n.put("cache_creation_input_tokens", c.cacheCreationInputTokens());
            putTraffic(n.putObject("traffic"), c.traffic());
        });
        var other = root.putObject("unattributed");
        if (unattributedSince != null) other.put("counting_since", unattributedSince.toEpochMilli());
        putTraffic(other, unattributed);
        return root.toString();
    }

    private static void putTraffic(ObjectNode n, Traffic traffic) {
        putCounts(n, traffic.total());
        var domains = n.putObject("domains");
        traffic.byDomain().forEach((domain, counts) -> putCounts(domains.putObject(domain), counts));
    }

    private static void putCounts(ObjectNode n, Counts c) {
        n.put("requests", c.requests());
        n.put("in_flight", c.inFlight());
        if (c.lastActivityAt() != null) n.put("last_activity_at", c.lastActivityAt().toEpochMilli());
        n.put("request_bytes", c.requestBytes());
        n.put("response_bytes", c.responseBytes());
    }

    static ProxyActivity parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            throw new Unavailable("the isx proxy's activity could not be read (" + e.getMessage() + ")");
        }
        var instances = new HashMap<String, Instance>();
        root.path("instances").properties().forEach(e -> {
            var n = e.getValue();
            instances.put(e.getKey(), new Instance(instant(n.path("counting_since")),
                    n.path("requests").asLong(), n.path("in_flight").asLong(),
                    instant(n.path("last_request_at")), instant(n.path("last_response_at")),
                    n.path("input_tokens").asLong(), n.path("output_tokens").asLong(),
                    n.path("cache_read_input_tokens").asLong(), n.path("cache_creation_input_tokens").asLong(),
                    traffic(n.path("traffic"))));
        });
        var other = root.path("unattributed");
        return new ProxyActivity(Map.copyOf(instances), instant(other.path("counting_since")), traffic(other));
    }

    /** A proxy older than #935 reports no traffic: {@link Traffic#NONE}. */
    private static Traffic traffic(JsonNode n) {
        if (!n.isObject()) return Traffic.NONE;
        var domains = new HashMap<String, Counts>();
        n.path("domains").properties().forEach(e -> domains.put(e.getKey(), counts(e.getValue())));
        return new Traffic(counts(n), Map.copyOf(domains));
    }

    private static Counts counts(JsonNode n) {
        return new Counts(n.path("requests").asLong(), n.path("in_flight").asLong(),
                instant(n.path("last_activity_at")), n.path("request_bytes").asLong(), n.path("response_bytes").asLong());
    }

    private static Instant instant(JsonNode millis) {
        return millis.canConvertToLong() && millis.asLong() > 0 ? Instant.ofEpochMilli(millis.asLong()) : null;
    }
}
