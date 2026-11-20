package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The runs of {@code isx mcp} tasks the event log holds (#2428), each with the model calls booked
 * to it. {@code isx mcp} writes a {@code kind: task} line when a run starts ({@code op: start},
 * with the caller's {@code activity}) and when it sees the run end ({@code op: end}, with its state
 * and exit code); a model call of the same instance belongs to the run whose start and end enclose
 * it.
 *
 * <p>Runs and calls are matched by instance name and time, and by incarnation when both lines have
 * one, so a line written without an incarnation still finds its run. A start written without one,
 * as a fresh instance's is, takes the incarnation of the first call in its run that names one. An agent's run takes a call
 * before a command's does: a background command beside an agent rarely makes model calls of its
 * own. A call two runs of the same rank enclose is {@code shared}, never split or guessed; one no
 * run encloses is the instance's alone ({@code outside}).
 *
 * <p>A run whose end was never seen (the session died, or nobody asked) is closed at the next
 * start of a run of its kind in its instance, or of another incarnation of the name, else at the
 * last model call its incarnation made. A run whose start
 * line rolled out of the log, or was never written, is not listed.
 */
public final class TaskRuns {

    /** What the model calls booked somewhere cost. */
    public record Usage(long modelCalls, long inputTokens, long outputTokens, long cacheReadTokens,
                        long cacheCreationTokens) {
        public static final Usage NONE = new Usage(0, 0, 0, 0, 0);

        /** Every token, cached ones included. */
        public long tokens() {
            return inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens;
        }

        Usage plus(Call c) {
            return new Usage(modelCalls + 1, inputTokens + c.input, outputTokens + c.output,
                    cacheReadTokens + c.cacheRead, cacheCreationTokens + c.cacheCreation);
        }
    }

    /**
     * One run of a task. {@code kind} is {@code agent} or {@code command}; {@code activity} is the
     * caller's tag, empty for none; {@code end} is null when no end line was seen, and then
     * {@code state} is empty and {@code exitCode} null.
     */
    public record Run(String instance, String incarnation, String taskId, int run, String kind, String activity,
                      Instant start, Instant end, String state, Integer exitCode, Usage usage) {}

    /** An instance's runs, oldest first, and what the calls no single run took cost. */
    public record Booked(List<Run> runs, Usage shared, Usage outside) {
        public static final Booked NONE = new Booked(List.of(), Usage.NONE, Usage.NONE);
    }

    /** A name's runs, and its unbooked calls by the incarnation they carried ({@code ""} for none). */
    private record Named(List<Run> runs, Map<String, Usage> shared, Map<String, Usage> outside) {}

    public static final TaskRuns EMPTY = new TaskRuns(Map.of());

    private final Map<String, Named> byName;

    private TaskRuns(Map<String, Named> byName) {
        this.byName = byName;
    }

    /**
     * The runs of the instance of this name and incarnation, and its calls outside them. Empty for
     * an instance with no task line, as in a log no {@code isx mcp} wrote to.
     */
    public Booked of(String name, String incarnation) {
        var named = byName.get(name);
        if (named == null) return Booked.NONE;
        if (incarnation == null || incarnation.isEmpty()) {
            return new Booked(named.runs(), sum(named.shared().values()), sum(named.outside().values()));
        }
        // As EventLogReader.Log.of: the incarnation's own, and what was written without one
        var runs = named.runs().stream().filter(r -> compatible(r.incarnation(), incarnation)).toList();
        return new Booked(runs, sum(List.of(named.shared().getOrDefault(incarnation, Usage.NONE), named.shared().getOrDefault("", Usage.NONE))),
                sum(List.of(named.outside().getOrDefault(incarnation, Usage.NONE), named.outside().getOrDefault("", Usage.NONE))));
    }

    private static Usage sum(java.util.Collection<Usage> all) {
        var total = Usage.NONE;
        for (var u : all) {
            total = new Usage(total.modelCalls() + u.modelCalls(), total.inputTokens() + u.inputTokens(),
                    total.outputTokens() + u.outputTokens(), total.cacheReadTokens() + u.cacheReadTokens(),
                    total.cacheCreationTokens() + u.cacheCreationTokens());
        }
        return total;
    }

    private static boolean compatible(String a, String b) {
        return a.isEmpty() || b.isEmpty() || a.equals(b);
    }

    private record Call(Instant at, String incarnation, long input, long output, long cacheRead, long cacheCreation) {}

    private static final class Open {
        final String taskId;
        final int run;
        String instance, incarnation = "", kind = "", activity = "", state = "";
        Instant start, end;
        /** When the call this run's incarnation was taken from was made, for a start written without one. */
        Instant inferredAt;
        Integer exit;
        Usage usage = Usage.NONE;

        Open(Key key) {
            instance = key.instance();
            taskId = key.taskId();
            run = key.run();
        }
    }

    /** A run is its instance's: a task id read back from a guest never closes another instance's run. */
    private record Key(String instance, String taskId, int run) {}

    /** Collects the lines as {@link EventLogReader} reads them, in whatever order the files hold them. */
    static final class Reading {
        private final Map<Key, Open> runs = new HashMap<>();
        private final Map<String, List<Call>> calls = new HashMap<>();

        /** A {@code kind: task} line. */
        void task(JqValue line, Instant at, String name, String incarnation) {
            var taskId = line.pathText("task_id", "");
            var number = line.path("run").asLong(0);
            if (taskId.isEmpty() || number < 1 || number > Integer.MAX_VALUE) return;
            var run = runs.computeIfAbsent(new Key(name, taskId, (int) number), Open::new);
            switch (line.pathText("op", "")) {
                case "start" -> {
                    // The first start wins: a repeat cannot move a run
                    if (run.start != null) return;
                    run.start = at;
                    run.incarnation = incarnation;
                    run.kind = line.pathText("task_kind", "");
                    run.activity = line.pathText("activity", "");
                }
                case "end" -> {
                    // The earliest end wins: a session that adopted the task may see it end again
                    if (run.end != null && !at.isBefore(run.end)) return;
                    run.end = at;
                    run.state = line.pathText("state", "");
                    var exit = line.path("exit_code");
                    run.exit = exit.isNumber() ? (int) exit.asLong(0) : null;
                }
                default -> { }
            }
        }

        /**
         * The start of the next run, by start, of the same kind as run {@code i}, or, unless
         * {@code kindOnly}, of another incarnation of the name; null for none. A run not seen to
         * end is closed there: the session ran no second agent in one instance, nor did the instance outlive its name.
         */
        private static Instant nextStart(List<Open> list, int i, boolean kindOnly) {
            var r = list.get(i);
            for (int j = i + 1; j < list.size(); j++) {
                var later = list.get(j);
                if (later.kind.equals(r.kind) || !kindOnly && !compatible(later.incarnation, r.incarnation)) return later.start;
            }
            return null;
        }

        /** A model call, kept until every task line is read. */
        void call(String name, String incarnation, Instant at, long input, long output, long cacheRead, long cacheCreation) {
            calls.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new Call(at, incarnation, input, output, cacheRead, cacheCreation));
        }

        TaskRuns done() {
            var byInstance = new HashMap<String, List<Open>>();
            runs.values().forEach(run -> {
                if (run.start != null) byInstance.computeIfAbsent(run.instance, k -> new ArrayList<>()).add(run);
            });
            var result = new HashMap<String, Named>();
            byInstance.forEach((name, list) -> {
                list.sort(Comparator.comparing((Open r) -> r.start));
                var mine = calls.getOrDefault(name, List.of());
                // A start written without an incarnation (a fresh instance's) takes the one its first call names
                for (int i = 0; i < list.size(); i++) {
                    var r = list.get(i);
                    if (!r.incarnation.isEmpty()) continue;
                    var bound = r.end != null ? r.end : nextStart(list, i, true);
                    for (var c : mine) {
                        if (c.incarnation.isEmpty() || c.at.isBefore(r.start) || bound != null && c.at.isAfter(bound)) continue;
                        if (r.inferredAt == null || c.at.isBefore(r.inferredAt)) {
                            r.incarnation = c.incarnation;
                            r.inferredAt = c.at;
                        }
                    }
                }
                var until = new HashMap<Open, Instant>();
                for (int i = 0; i < list.size(); i++) {
                    var r = list.get(i);
                    var close = r.end != null ? r.end : nextStart(list, i, false);
                    if (close == null) {
                        // Never seen to end, and the last of its kind: its instance's last call it could have made
                        close = r.start;
                        for (var c : mine) {
                            if (c.at.isAfter(close) && compatible(r.incarnation, c.incarnation)) close = c.at;
                        }
                    }
                    until.put(r, close);
                }
                var shared = new HashMap<String, Usage>();
                var outside = new HashMap<String, Usage>();
                for (var c : mine) {
                    var agents = new ArrayList<Open>();
                    var commands = new ArrayList<Open>();
                    for (var r : list) {
                        if (c.at.isBefore(r.start) || c.at.isAfter(until.get(r))) continue;
                        if (!compatible(r.incarnation, c.incarnation)) continue;
                        (r.kind.equals("command") ? commands : agents).add(r);
                    }
                    var candidates = agents.isEmpty() ? commands : agents;
                    if (candidates.size() == 1) candidates.getFirst().usage = candidates.getFirst().usage.plus(c);
                    else (candidates.isEmpty() ? outside : shared).merge(c.incarnation, Usage.NONE.plus(c), (a, b) -> sum(List.of(a, b)));
                }
                var listed = list.stream().map(r -> new Run(name, r.incarnation, r.taskId, r.run,
                        r.kind, r.activity, r.start, r.end, r.state, r.exit, r.usage)).toList();
                result.put(name, new Named(listed, Map.copyOf(shared), Map.copyOf(outside)));
            });
            return new TaskRuns(Map.copyOf(result));
        }
    }
}
