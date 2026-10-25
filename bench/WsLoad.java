import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The WebSocket load of {@code bench/run.sh --load=websocket}, run inside an instance as Hyperfoil
 * is for the model loads (Hyperfoil speaks no WebSocket). Codex keeps a WebSocket to
 * {@code api.openai.com} and sends a turn, which the server answers with a stream of events: each
 * user here opens one with the instance's own OpenAI proof, sends {@link #TURNS_PER_SOCKET} turns
 * of about 2 KiB, each answered by the stub's streamed reply, and then opens the next, so the
 * handshake (proof check, key injection, the upstream dial) is part of the load as well as the relay.
 *
 * <p>Phases run one after another at a fixed number of users, warmup first, and the totals are
 * printed in the shape of Hyperfoil's {@code /run/<id>/stats/total}, which run.sh already reads. A
 * request is one turn: its time runs from sending it to the {@code response.completed} event; a
 * refused handshake counts as a failed one.
 *
 * <p>Usage: {@code java WsLoad.java <wss-uri> <proof> <phase>=<users>x<seconds>[,...]}, a phase
 * named {@code warmup} being left out of the results as Hyperfoil's warmups are.
 */
public class WsLoad {

    static final int TURNS_PER_SOCKET = 10;
    static final String TURN = "{\"type\":\"response.create\",\"response\":{\"model\":\"gpt-5-codex\",\"input\":["
            + "{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\""
            + "the agent edits files runs tests and reads the build output before it answers ".repeat(24)
            + "\"}]}]}}";

    record Phase(String name, int users, int seconds) {}

    public static void main(String[] args) throws Exception {
        var uri = URI.create(args[0]);
        var proof = args[1];
        var phases = new ArrayList<Phase>();
        for (var spec : args[2].split(",")) {
            var name = spec.substring(0, spec.indexOf('='));
            var shape = spec.substring(spec.indexOf('=') + 1).split("x");
            phases.add(new Phase(name, Integer.parseInt(shape[0]), Integer.parseInt(shape[1])));
        }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var statistics = new ArrayList<String>();
        for (var phase : phases) {
            statistics.add(run(client, uri, proof, phase));
        }
        System.out.println("{\"statistics\":[" + String.join(",", statistics) + "]}");
    }

    static String run(HttpClient client, URI uri, String proof, Phase phase) throws Exception {
        var times = Collections.synchronizedList(new ArrayList<Long>());
        var failed = new AtomicInteger();
        var start = System.currentTimeMillis();
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(phase.seconds());
        var users = new ArrayList<Thread>();
        for (int u = 0; u < phase.users(); u++) {
            users.add(Thread.ofVirtual().start(() -> {
                while (System.nanoTime() < end) {
                    socket(client, uri, proof, end, times, failed);
                }
            }));
        }
        for (var user : users) user.join();
        var stop = System.currentTimeMillis();
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        var ok = sorted.size();
        return "{\"phase\":\"" + phase.name() + "\",\"isWarmup\":" + phase.name().equals("warmup")
                + ",\"summary\":{\"requestCount\":" + (ok + failed.get()) + ",\"startTime\":" + start
                + ",\"endTime\":" + stop + ",\"maxResponseTime\":" + (ok == 0 ? 0 : sorted.get(ok - 1))
                + ",\"percentileResponseTime\":{\"50.0\":" + percentile(sorted, 0.5) + ",\"99.0\":"
                + percentile(sorted, 0.99) + ",\"99.9\":" + percentile(sorted, 0.999) + "}"
                + ",\"extensions\":{\"http\":{\"status_2xx\":" + ok + "}}}}";
    }

    static long percentile(List<Long> sorted, double p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }

    /** One socket: up to {@link #TURNS_PER_SOCKET} turns, each timed. */
    static void socket(HttpClient client, URI uri, String proof, long end, List<Long> times, AtomicInteger failed) {
        var listener = new Listener();
        WebSocket ws;
        try {
            ws = client.newWebSocketBuilder().header("Authorization", "Bearer " + proof)
                    .connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, listener).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            failed.incrementAndGet();
            sleep();
            return;
        }
        try {
            for (int t = 0; t < TURNS_PER_SOCKET && System.nanoTime() < end; t++) {
                var done = listener.expect();
                var sent = System.nanoTime();
                ws.sendText(TURN, true).get(15, TimeUnit.SECONDS);
                done.get(15, TimeUnit.SECONDS);
                times.add(System.nanoTime() - sent);
            }
        } catch (Exception e) {
            failed.incrementAndGet();
            ws.abort();
            return;
        }
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            ws.abort();  // every turn was answered; how the socket ends is no request of its own
        }
    }

    static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Completes each turn's future on the event that ends its streamed reply. */
    static final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private volatile CompletableFuture<Void> turn = new CompletableFuture<>();

        CompletableFuture<Void> expect() {
            turn = new CompletableFuture<>();
            return turn;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                if (text.indexOf("\"response.completed\"") >= 0) turn.complete(null);
                text.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            turn.completeExceptionally(new IllegalStateException("closed " + status));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            turn.completeExceptionally(error);
        }
    }
}
