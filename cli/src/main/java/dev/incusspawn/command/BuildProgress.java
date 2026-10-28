package dev.incusspawn.command;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.CpuInfo;
import dev.incusspawn.util.OutputFormat;
import dev.incusspawn.util.TerminalProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.regex.Pattern;


/**
 * How a build shows its steps: the one-line spinners for dnf, single operations and
 * self-reporting steps such as a download, and the per-step progress state they render.
 */
final class BuildProgress {

    private BuildProgress() {}

    enum StepState { RUNNING, DONE, FAILED }

    /** Progress state for one repo's clone→prime pipeline. {@code activity} is the
     *  live verb shown while RUNNING (e.g. "Cloning", then "Priming"); {@code note}
     *  is a dim annotation shown on success; {@code detail} is a concise one-line
     *  error for the inline display; {@code log} is the full captured command output,
     *  shown on failure so the diagnostic isn't reduced to the single inline line;
     *  {@code step} names the step that failed for that output's heading, such as "prime",
     *  when it is not the batch's own verb, and {@code command} is the command it ran.
     *  The detail and log quote what a guest command printed, such as a prime the cloned
     *  repository controls, so neither holds anything that could drive a terminal wherever
     *  it is shown or saved ({@link BuildOutput#guestText}, #1588). */
    record StepProgress(StepState state, String activity, String note, boolean noteHighlight, String detail, String log,
                        String step, String command) {
        StepProgress {
            activity = activity == null ? null : OutputFormat.oneLine(activity);
            note = note == null ? null : OutputFormat.oneLine(note);
            detail = detail == null ? null : OutputFormat.oneLine(BuildOutput.guestText(detail));
            log = log == null ? null : BuildOutput.guestText(log);
            step = step == null ? null : OutputFormat.oneLine(step);
            command = command == null ? null : BuildOutput.guestText(command);
        }

        static StepProgress running(String activity) { return new StepProgress(StepState.RUNNING, activity, null, false, null, null, null, null); }
        static StepProgress running(String activity, String detail) { return new StepProgress(StepState.RUNNING, activity, null, false, detail, null, null, null); }
        static StepProgress done(String note) { return new StepProgress(StepState.DONE, null, note, false, null, null, null, null); }
        static StepProgress doneHighlight(String note) { return new StepProgress(StepState.DONE, null, note, true, null, null, null, null); }
        static StepProgress failed(String detail, String log) { return failed(null, null, detail, log); }
        static StepProgress failed(String step, String command, String detail, String log) {
            return new StepProgress(StepState.FAILED, null, null, false, detail, log, step, command);
        }
    }

    /**
     * Run {@code work} behind a one-line spinner whose dim detail is re-read from
     * {@code detail} on every frame, for steps that report their own progress. {@code work}
     * records the outcome in {@code state[0]}; a DONE note is the whole completion line. On a
     * plain (non-ANSI) terminal the label is printed up front, since nothing animates there.
     */
    static void runLiveStep(String rawLabel, java.util.function.Supplier<String> detail,
                                    String failureMessage,
                                    Consumer<AtomicReferenceArray<StepProgress>> work) {
        var label = OutputFormat.oneLine(rawLabel);  // it can hold definition text (#1610)
        var state = new AtomicReferenceArray<StepProgress>(1);
        state.set(0, StepProgress.running(""));
        if (!TerminalProgress.isAnsiTerminal()) BuildOutput.step(label + "...");
        TerminalProgress.run(1, 1,
                idx -> runSpinnerWork(work, state),
                (idx, frame) -> formatLiveStepLine(label, state.get(0), detail, frame),
                idx -> {});
        // A DONE note is the whole completion line, the label having been said up front
        reportPlain(state.get(0), null);
        finishSpinner(label, label + " failed", failureMessage, state);
    }

    static String formatLiveStepLine(String label, StepProgress p, java.util.function.Supplier<String> detail,
                                     int frame) {
        return switch (p.state()) {
            case RUNNING -> {
                var line = BuildOutput.indent()
                        + TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length] + " " + label;
                var d = detail.get();
                yield d == null || d.isEmpty() ? line : line + "  \033[2m" + d + "\033[0m"; // raw ANSI: animated line
            }
            case DONE -> BuildOutput.indent() + BuildOutput.CHECK + " " + p.note();
            case FAILED -> formatDnfLine(label, p, frame);
        };
    }

    /**
     * What a download is doing, recorded by {@link DownloadCache} as it happens and turned
     * into text only when the spinner redraws, so a chunk costs a few field writes.
     */
    static final class TransferProgress implements DownloadCache.Listener {
        // Set when the body starts, so re-hashing a stale cached copy and connecting do not
        // count against the rate, the time left or the reported duration.
        private volatile long start;
        // Where the body started from: a resumed attempt continues bytes an earlier one fetched,
        // which the rate must not count
        private volatile long from;
        // When the first attempt's body started: a resumed download took all its attempts
        private volatile long began;
        private volatile String phase = "connecting";
        private volatile long bytes;
        private volatile long total = -1;
        private volatile boolean fetched;
        private volatile long end;

        @Override
        public void verifyingCached() {
            phase = "verifying cached copy";
        }

        @Override
        public void received(long bytes, long total) {
            if (start == 0) {
                start = System.nanoTime();
                from = bytes;
                if (began == 0) began = start;
            }
            this.bytes = bytes;
            this.total = total;
            fetched = true;
            phase = null;
        }

        @Override
        public void retrying(String reason, int attempt, int attempts) {
            // The next attempt resumes or starts from byte 0; either way its rate and time left
            // must not count the failed one or the pause.
            start = 0;
            bytes = 0;
            total = -1;
            phase = reason + ", retrying (attempt " + attempt + " of " + attempts + ")";
        }

        @Override
        public void verifying() {
            end = System.nanoTime();
            phase = "verifying checksum";
        }

        /** Whether the body was fetched (or copied from a {@code file://} URL) rather than reused from the cache. */
        boolean fetched() {
            return fetched;
        }

        String detail() {
            var p = phase;
            return p != null ? p : describeTransfer(bytes, from, total, System.nanoTime() - start);
        }

        /**
         * Size and duration of a finished download, e.g. {@code 1.9 GB in 5m 12s}: from its first
         * byte, across retries, since a resumed attempt fetched only the rest.
         */
        String summary() {
            var finished = end != 0 ? end : System.nanoTime();
            return CleanCommand.formatSize(bytes) + " in " + formatDuration(finished - began);
        }
    }

    /**
     * {@code 42%  812.0 MB / 1.9 GB  3.1 MB/s  4m 30s left}, or just the size and rate when the
     * server sent no length. The rate counts the bytes since {@code from} (where a resumed download
     * started), and waits for a full second of data, since the first chunks alone would project a
     * wildly wrong one.
     */
    static String describeTransfer(long bytes, long from, long total, long elapsedNanos) {
        var sb = new StringBuilder();
        if (total > 0) {
            sb.append(Math.min(100, bytes * 100 / total)).append("%  ")
                    .append(CleanCommand.formatSize(bytes)).append(" / ").append(CleanCommand.formatSize(total));
        } else {
            sb.append(CleanCommand.formatSize(bytes));
        }
        if (elapsedNanos >= 1_000_000_000L && bytes > from) {
            double perSecond = (bytes - from) / (elapsedNanos / 1e9);
            sb.append("  ").append(CleanCommand.formatSize((long) perSecond)).append("/s");
            if (total > bytes) {
                sb.append("  ").append(formatDuration((long) ((total - bytes) / perSecond * 1e9))).append(" left");
            }
        }
        return sb.toString();
    }

    /** {@code 42s}, {@code 5m 12s} or {@code 1h 03m}. */
    static String formatDuration(long nanos) {
        long seconds = nanos / 1_000_000_000L;
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + String.format("%02ds", seconds % 60);
        return (seconds / 3600) + "h " + String.format("%02dm", (seconds % 3600) / 60);
    }

    /**
     * Shared dnf options: keep the download cache, cap metadata refresh, skip
     * docs, and parallelize downloads. {@code max_parallel_downloads} is bounded
     * to dnf's practical ceiling (20) and scaled to the host's logical cores so
     * beefier machines fetch more concurrently — the download phase is the only
     * parallelizable part; the rpm transaction itself is serial.
     */
    private static final String[] DNF_BASE_OPTS = {
            "--setopt=keepcache=true",
            "--setopt=metadata_expire=3600",
            "--setopt=tsflags=nodocs",
            "--setopt=max_parallel_downloads=" + Math.min(20, Math.max(8, CpuInfo.logicalCores())),
    };

    /** Build a full {@code dnf} command line: {@code dnf <shared opts> <rest>}. */
    static String[] dnfCommand(String... rest) {
        var cmd = new ArrayList<String>();
        cmd.add("dnf");
        cmd.addAll(List.of(DNF_BASE_OPTS));
        cmd.addAll(List.of(rest));
        return cmd.toArray(String[]::new);
    }

    /** dnf5's non-TTY per-step progress line, e.g.
     *  {@code [3/6] Installing setup-0:2.15.0-28.fc  100% | 26 MiB/s | ...} or a
     *  download line {@code [1/2] filesystem-0:3.18-52.fc44.aarch64  100% | ...}.
     *  Group 1/2 are the counters, group 3 is the action+package (dnf truncates it
     *  to its assumed 80-col width). */
    private static final Pattern DNF_STEP = Pattern.compile("^\\[(\\d+)/(\\d+)]\\s+(.*)$");

    /**
     * Run a dnf command behind an animated one-line spinner. dnf's verbose output
     * is streamed through a parser (not echoed to the terminal) so the spinner can
     * show live "N/M — current package" feedback from dnf's own progress lines,
     * keeping isx's warnings visible instead of buried in a flood. On failure it
     * clears metadata and retries once with {@code --refresh}; if that also fails
     * the end of the captured output is a warning and {@code failureMessage} is thrown with all of it.
     *
     * @param rawLabel the full phrase for the spinner line (e.g. "Installing base packages")
     */
    static void runDnf(Container container, String rawLabel, String failureMessage, String... args) {
        var label = OutputFormat.oneLine(rawLabel);  // it can hold definition text (#1610)
        var state = new AtomicReferenceArray<StepProgress>(1);
        state.set(0, StepProgress.running("", "starting"));
        var started = System.nanoTime();
        TerminalProgress.run(1, 1,
                idx -> dnfWork(container, args, state),
                (idx, frame) -> formatDnfLine(label, state.get(0), frame,
                        (System.nanoTime() - started) / 1_000_000),
                idx -> {});
        reportPlain(state.get(0), label + "... done.");
        finishSpinner(label, label + " failed", failureMessage, state);
    }

    /** The install/retry work for {@link #runDnf}, recording progress in {@code state[0]}.
     *  A thrown exception (e.g. an Incus transport error) is recorded as a failed state
     *  rather than propagated, since {@code TerminalProgress} swallows task exceptions —
     *  an uncaught one would leave the step stuck RUNNING and lose the real cause. */
    private static void dnfWork(Container container, String[] args, AtomicReferenceArray<StepProgress> state) {
        try {
            var log = new StringBuilder();
            int code = container.execLines(line -> onDnfLine(line, log, state), args);
            if (code == 0) {
                state.set(0, StepProgress.done(null));
                return;
            }
            // Metadata may be stale — clear it and retry once with --refresh.
            state.set(0, StepProgress.running("", "retrying with --refresh"));
            container.sh("dnf clean metadata");
            var retryArgs = new ArrayList<>(List.of(args));
            retryArgs.add(1, "--refresh");
            var retryLog = new StringBuilder();
            int retryCode = container.execLines(line -> onDnfLine(line, retryLog, state),
                    retryArgs.toArray(String[]::new));
            if (retryCode == 0) {
                state.set(0, StepProgress.done("succeeded after refresh"));
            } else {
                var out = retryLog.toString();
                state.set(0, StepProgress.failed(lastNonEmptyLine(out), out));
            }
        } catch (RuntimeException e) {
            state.set(0, StepProgress.failed(e.getMessage(), stackTrace(e)));
        }
    }

    /** Accumulate a streamed dnf line into {@code log} and, if it's a progress line,
     *  update the spinner's live detail to "N/M — package". */
    static void onDnfLine(String line, StringBuilder log, AtomicReferenceArray<StepProgress> state) {
        synchronized (log) { log.append(line).append('\n'); }
        var m = DNF_STEP.matcher(line.strip());
        if (!m.matches()) return;
        // Drop the trailing "100% | rate | size | time" progress bar. dnf truncates
        // the action/package to a fixed column, so there may be only a single space
        // before the percentage — key off the "<n>% |" shape, not the spacing.
        var body = m.group(3).replaceAll("\\s+\\d+%\\s*\\|.*$", "").strip();
        if (body.isEmpty() || body.equals("Total")) return; // skip the download subtotal line
        state.set(0, StepProgress.running("", m.group(1) + "/" + m.group(2) + "  " + shortenNevra(body)));
    }

    /** dnf's non-TTY column truncates the version/arch tail off each NEVRA anyway, and the
     *  version is just noise in a live progress line, so reduce {@code name-epoch:ver-rel.arch}
     *  to its bare package name (everything before the {@code -<epoch>:} marker). Action-only
     *  lines ("Verify package files") and names truncated before the epoch are left untouched. */
    static String shortenNevra(String body) {
        return body.replaceFirst("-\\d+:.*$", "");
    }

    /** Render the dnf spinner line: {@code     ⠋ <label>  <dim live detail>}, then {@code ✓ <label>}. */
    static String formatDnfLine(String label, StepProgress p, int frame) {
        return formatDnfLine(label, p, frame, 0);
    }

    static String formatDnfLine(String label, StepProgress p, int frame, long elapsedMs) {
        var sb = new StringBuilder(BuildOutput.indent());
        switch (p.state()) {
            case RUNNING -> sb.append(TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length])
                    .append(' ').append(label);
            case DONE    -> sb.append(BuildOutput.CHECK).append(' ').append(label);
            case FAILED  -> sb.append(BuildOutput.CROSS).append(' ').append(label);
        }
        if (p.state() == StepState.RUNNING && p.detail() != null && !p.detail().isEmpty()) {
            sb.append("  \033[2m").append(p.detail()).append("\033[0m"); // raw ANSI: animated line
        }
        if (p.state() == StepState.DONE && p.note() != null && !p.note().isEmpty()) {
            sb.append(" \033[2m(").append(p.note()).append(")\033[0m"); // raw ANSI: animated line
        }
        if (p.state() == StepState.DONE && elapsedMs >= 2000) {
            sb.append("  \033[2m").append(BuildOutput.formatElapsed(elapsedMs)).append("\033[0m"); // raw ANSI: animated line
        }
        if (p.state() == StepState.FAILED && p.detail() != null && !p.detail().isEmpty()) {
            sb.append("  \033[31m").append(p.detail()).append("\033[0m"); // raw ANSI: animated line
        }
        return sb.toString();
    }

    /**
     * Off an ANSI terminal, a one-task step that is done: {@code done} as a step, with its note, or
     * the note alone when {@code done} is null. A failed one is {@link #finishSpinner}'s to report.
     */
    private static void reportPlain(StepProgress p, String done) {
        if (TerminalProgress.isAnsiTerminal() || p == null || p.state() != StepState.DONE) return;
        var note = p.note() == null || p.note().isEmpty() ? null : p.note();
        if (done == null) {
            if (note != null) BuildOutput.step(note);
        } else {
            BuildOutput.step(note != null ? done + " (" + note + ")" : done);
        }
    }

    /**
     * Run a single operation behind an animated one-line spinner (mirroring the
     * clone/prime display) instead of streaming its output. {@code work} performs
     * the operation with captured exec and records the terminal {@link StepProgress}
     * in {@code state[0]}; it may set an intermediate {@code running(...)} to advance
     * the verb mid-flight. On a non-DONE result the end of the captured log is a
     * warning and {@code failureMessage} is thrown with all of it.
     */
    static void runWithSpinner(String activity, String rawLabel, String failureMessage,
                                Consumer<AtomicReferenceArray<StepProgress>> work) {
        var label = OutputFormat.oneLine(rawLabel);  // it can hold definition text (#1610)
        var state = new AtomicReferenceArray<StepProgress>(1);
        state.set(0, StepProgress.running(activity));
        TerminalProgress.run(1, 1,
                idx -> runSpinnerWork(work, state),
                (idx, frame) -> formatStepLine(label, null, state.get(0), frame, "Done"),
                idx -> {});
        reportPlain(state.get(0), "Done " + label);
        finishSpinner(label, activity + " failed for " + label, failureMessage, state);
    }

    /** Run a {@link #runWithSpinner} task, converting a thrown exception into a recorded
     *  failed state. {@code TerminalProgress} swallows task exceptions, so without this the
     *  step would stay RUNNING and {@code finishSpinner} would throw the generic failure
     *  message with no captured cause. */
    static void runSpinnerWork(Consumer<AtomicReferenceArray<StepProgress>> work,
                                       AtomicReferenceArray<StepProgress> state) {
        try {
            work.accept(state);
        } catch (RuntimeException e) {
            state.set(0, StepProgress.failed(e.getMessage(), stackTrace(e)));
        }
    }

    /** Render a throwable's stack trace to a string for a failed step's captured log. */
    private static String stackTrace(Throwable t) {
        var sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /**
     * After a one-task spinner completes, surface a non-DONE result and throw
     * {@code failureMessage} with all of the captured log for the failure report. On an ANSI
     * terminal the step's line already shows it failed, so the end of the log (if any) is a
     * warning under it. Off one nothing showed the step, so {@code failed} and the detail are the
     * one warning, a single indented line, with the end of the log under it when there is one.
     */
    private static void finishSpinner(String label, String failed, String failureMessage,
                                      AtomicReferenceArray<StepProgress> state) {
        var progress = state.get(0);
        if (progress != null && progress.state() == StepState.DONE) return;

        var detail = progress != null && progress.detail() != null && !progress.detail().isEmpty()
                ? ": " + progress.detail() : "";
        var failure = new IncusException(failureMessage + detail);
        var hasLog = progress != null && progress.log() != null && !progress.log().isBlank();
        // The log is guest text, made safe already; the report keeps what the warning leaves out.
        // The kind #1791's design gives it: the step's failure, the end of its log under it
        var tail = hasLog ? BuildOutput.lastOf(BuildOutput.guestLines(progress.log())) : new String[0];
        if (TerminalProgress.isAnsiTerminal()) {
            if (hasLog) BuildOutput.warn(label + " failed", tail);
        } else if (hasLog) {
            // A detail that is the log's last line is said once, under the header
            var last = tail.length == 0 ? "" : tail[tail.length - 1].strip();
            BuildOutput.warn(last.equals(progress.detail()) ? failed : failed + detail, tail);
        } else {
            // One line on stderr, with no closing blank line on stdout, as the old plain line had none
            BuildOutput.warnAside(failed + detail);
        }
        if (!hasLog) throw failure;
        throw failure.withStepOutput(label + " failed:\n" + progress.log().strip() + "\n\n");
    }

    /** Turn a captured exec result into a terminal {@link StepProgress}. */
    static StepProgress stepFrom(IncusClient.ExecResult result) {
        if (result.success()) return StepProgress.done(null);
        var combined = combinedOutput(result);
        return StepProgress.failed(lastNonEmptyLine(combined), combined);
    }

    /** Render one animated progress line, aligned across running/done/failed states.
     *  The running verb comes from the live state ({@code activity}) so a task can
     *  advance through phases (e.g. Cloning → Priming) within one line. The label and
     *  context can quote definition text, such as a repository's URL, so they are shown as
     *  text (#1610). */
    static String formatStepLine(String label, String dimContext, StepProgress progress, int frame,
                                 String doneWord) {
        label = OutputFormat.oneLine(label);
        var runningWord = progress.activity() != null ? progress.activity() : "Working";
        var sb = new StringBuilder(BuildOutput.indent());
        switch (progress.state()) {
            case RUNNING -> sb.append(TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length])
                    .append(" \033[2m").append(padStatus(runningWord)).append("\033[0m "); // raw ANSI: animated line
            case DONE    -> sb.append("\033[32m✓\033[0m \033[2m").append(padStatus(doneWord)).append("\033[0m "); // raw ANSI: animated line
            case FAILED  -> sb.append("\033[31m✗ ").append(padStatus("Failed")).append("\033[0m "); // raw ANSI: animated line
        }
        sb.append(label);
        // Live detail before the context, so a long URL is what a narrow terminal cuts off.
        if (progress.state() == StepState.RUNNING && progress.detail() != null && !progress.detail().isEmpty()) {
            sb.append("  \033[2m").append(progress.detail()).append("\033[0m"); // raw ANSI: animated line
        }
        if (dimContext != null && !dimContext.isEmpty()) {
            sb.append(" \033[2m(").append(OutputFormat.oneLine(dimContext)).append(")\033[0m"); // raw ANSI: animated line
        }
        if (progress.state() == StepState.DONE && progress.note() != null && !progress.note().isEmpty()) {
            if (progress.noteHighlight()) {
                sb.append(" \033[1m").append(progress.note()).append("\033[0m"); // raw ANSI: animated line
            } else {
                sb.append(" \033[2m").append(progress.note()).append("\033[0m"); // raw ANSI: animated line
            }
        }
        if (progress.state() == StepState.FAILED && progress.detail() != null && !progress.detail().isEmpty()) {
            sb.append("  \033[31m").append(progress.detail()).append("\033[0m"); // raw ANSI: animated line
        }
        return sb.toString();
    }

    private static String padStatus(String word) {
        return word.length() >= 8 ? word : word + " ".repeat(8 - word.length());
    }

    /** Full captured output (stdout + stderr) of an exec, for surfacing on failure. */
    static String combinedOutput(IncusClient.ExecResult result) {
        var out = result.stdout() == null ? "" : result.stdout().strip();
        var err = result.stderr() == null ? "" : result.stderr().strip();
        if (out.isEmpty()) return err;
        if (err.isEmpty()) return out;
        return out + "\n" + err;
    }

    /**
     * Last non-empty line of some text as a terminal shows it, or "" if none: a last line holding
     * only colour or a title escape is not the one that says what failed.
     */
    static String lastNonEmptyLine(String text) {
        if (text == null || text.isEmpty()) return "";
        String last = "";
        for (var line : BuildOutput.guestText(text).split("\n")) {
            var trimmed = line.strip();
            if (!trimmed.isEmpty()) last = trimmed;
        }
        return last;
    }
}
