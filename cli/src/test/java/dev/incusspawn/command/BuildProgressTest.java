package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildProgressTest {

    // --- StepProgress ---

    @Test
    void guestOutputInAStepCannotDriveTheTerminal() {
        // What a guest command printed, such as a repository's prime, is shown as text (#1588):
        // colour is dropped, and every other control a terminal acts on becomes a space.
        var p = BuildProgress.StepProgress.failed("\033[31mboom\033[0m\033]52;c;eA==\007\u009b2J",
                "\033[31mred\033[0m\r\n\033]0;pwned\007tab\there\n");
        assertEquals("boom ]52;c;eA==  2J", p.detail());
        assertEquals("red \n ]0;pwned tab\there\n", p.log());
        var running = BuildProgress.StepProgress.running("Installing", "1/2  \033]8;;x\007pkg");
        assertEquals("1/2   ]8;;x pkg", running.detail());
    }

    // --- formatStepLine ---

    @Test
    void formatStepLineShowsSpinnerWhileRunning() {
        var p = BuildProgress.StepProgress.running("Cloning");
        var line = BuildProgress.formatStepLine("alpha", "url", p, 0, "Ready");
        assertTrue(stripAnsi(line).contains("Cloning"), "should show running verb");
        assertTrue(line.contains("alpha"), "should show the label");
    }

    @Test
    void formatStepLineShowsLiveDetailAfterTheLabel() {
        var p = BuildProgress.StepProgress.running("Creating clean Git snapshot", "12s  Receiving objects:  45% (12345/67890)");
        var line = stripAnsi(BuildProgress.formatStepLine("quarkus", "https://github.com/quarkusio/quarkus.git", p, 0, "Ready"));
        assertTrue(line.indexOf("quarkus") < line.indexOf("Receiving objects"), line);
        assertTrue(line.indexOf("Receiving objects") < line.indexOf("(https://"), "the URL goes last: " + line);
        assertTrue(line.indexOf("quarkus") < 50, "the name survives an 80-column cut: " + line);
    }

    @Test
    void formatStepLineShowsCheckmarkAndNoteWhenDone() {
        var p = BuildProgress.StepProgress.done("via host reference");
        var line = BuildProgress.formatStepLine("alpha", "url", p, 0, "Ready");
        assertTrue(line.contains("✓"), "should show checkmark");
        assertTrue(line.contains("Ready"), "should show done verb");
        assertTrue(line.contains("via host reference"), "should show the done note");
    }

    @Test
    void formatStepLineShowsErrorDetailWhenFailed() {
        var p = BuildProgress.StepProgress.failed("fatal: nope", null);
        var line = BuildProgress.formatStepLine("alpha", "url", p, 0, "Ready");
        assertTrue(line.contains("✗"), "should show cross");
        assertTrue(line.contains("fatal: nope"), "should show the error detail");
    }

    @Test
    void formatStepLineAlignsLabelAcrossStates() {
        var running = stripAnsi(BuildProgress.formatStepLine("alpha", null,
                BuildProgress.StepProgress.running("Cloning"), 0, "Ready"));
        var done = stripAnsi(BuildProgress.formatStepLine("alpha", null,
                BuildProgress.StepProgress.done(null), 0, "Ready"));
        var failed = stripAnsi(BuildProgress.formatStepLine("alpha", null,
                BuildProgress.StepProgress.failed(null, null), 0, "Ready"));
        assertEquals(running.indexOf("alpha"), done.indexOf("alpha"), "label aligns RUNNING vs DONE");
        assertEquals(done.indexOf("alpha"), failed.indexOf("alpha"), "label aligns DONE vs FAILED");
    }

    // --- dnf progress parsing (real dnf5 5.4.2.1 non-TTY output) ---

    private static String dnfDetail(String line) {
        var state = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        state.set(0, BuildProgress.StepProgress.running("", "starting"));
        BuildProgress.onDnfLine(line, new StringBuilder(), state);
        return state.get(0).detail();
    }

    @Test
    void onDnfLineParsesTransactionStep() {
        // "[3/6] Reinstalling setup-0:2.15.0-28.fc 100% |  26.4 MiB/s | 730.6 KiB |  00m00s"
        // The version/arch tail is dropped, leaving the bare package name.
        assertEquals("3/6  Reinstalling setup",
                dnfDetail("[3/6] Reinstalling setup-0:2.15.0-28.fc 100% |  26.4 MiB/s | 730.6 KiB |  00m00s"));
    }

    @Test
    void onDnfLineParsesDownloadStep() {
        // "[1/2] filesystem-0:3.18-52.fc44.aarch64 100% |   2.5 MiB/s |   1.3 MiB |  00m01s"
        assertEquals("1/2  filesystem",
                dnfDetail("[1/2] filesystem-0:3.18-52.fc44.aarch64 100% |   2.5 MiB/s |   1.3 MiB |  00m01s"));
    }

    @Test
    void onDnfLineParsesMultiWordAction() {
        // No NEVRA, so nothing is stripped.
        assertEquals("1/6  Verify package files",
                dnfDetail("[1/6] Verify package files              100% | 222.0   B/s |   2.0   B |  00m00s"));
    }

    @Test
    void shortenNevraStripsVersionButKeepsHyphenatedName() {
        assertEquals("glibc-gconv-extra",
                BuildProgress.shortenNevra("glibc-gconv-extra-0:2.43-8.fc44.aarch64"));
        assertEquals("Installing make",
                BuildProgress.shortenNevra("Installing make-1:4.4.1-12.fc44.aarch64"));
        // A name truncated before the epoch marker has no "-<epoch>:" and is left as-is.
        assertEquals("some-very-long-package-nam",
                BuildProgress.shortenNevra("some-very-long-package-nam"));
    }

    @Test
    void onDnfLineSkipsDownloadSubtotal() {
        var state = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        state.set(0, BuildProgress.StepProgress.running("", "before"));
        BuildProgress.onDnfLine("[2/2] Total                             100% |   1.4 MiB/s |   1.5 MiB |  00m01s",
                new StringBuilder(), state);
        assertEquals("before", state.get(0).detail(), "the download 'Total' subtotal line should be ignored");
    }

    @Test
    void onDnfLineIgnoresNonProgressLines() {
        var state = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        state.set(0, BuildProgress.StepProgress.running("", "before"));
        var log = new StringBuilder();
        BuildProgress.onDnfLine("Running transaction", log, state);
        BuildProgress.onDnfLine("Repositories loaded.", log, state);
        assertEquals("before", state.get(0).detail(), "non-[N/M] lines shouldn't change the detail");
        assertTrue(log.toString().contains("Running transaction"), "all lines are still logged");
    }

    @Test
    void runSpinnerWorkRecordsThrownExceptionAsFailed() {
        // TerminalProgress swallows task exceptions, so runSpinnerWork must convert a
        // thrown exception into a FAILED state (with the cause captured) rather than let
        // the step stay RUNNING and lose the real error.
        var state = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        state.set(0, BuildProgress.StepProgress.running("Doing"));
        BuildProgress.runSpinnerWork(s -> { throw new RuntimeException("transport boom"); }, state);
        assertEquals(BuildProgress.StepState.FAILED, state.get(0).state(), "exception must yield a failed state");
        assertEquals("transport boom", state.get(0).detail());
        assertTrue(state.get(0).log() != null && state.get(0).log().contains("transport boom"),
                "the stack trace should be captured as the failure log");
    }

    @Test
    void runSpinnerWorkKeepsRecordedStateOnSuccess() {
        var state = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        state.set(0, BuildProgress.StepProgress.running("Doing"));
        BuildProgress.runSpinnerWork(s -> s.set(0, BuildProgress.StepProgress.done("ok")), state);
        assertEquals(BuildProgress.StepState.DONE, state.get(0).state());
    }

    @Test
    void formatDnfLineShowsLabelAndLiveDetail() {
        var p = BuildProgress.StepProgress.running("", "3/6  Installing foo");
        var line = stripAnsi(BuildProgress.formatDnfLine("Installing base packages", p, 0));
        assertTrue(line.contains("Installing base packages"), "shows the batch label");
        assertTrue(line.contains("3/6  Installing foo"), "shows the live per-package detail");
    }

    @Test
    void formatDnfLineShowsCheckWhenComplete() {
        var line = stripAnsi(BuildProgress.formatDnfLine("Installing base packages",
                BuildProgress.StepProgress.done(null), 0));
        assertEquals("    ✓ Installing base packages", line,
                "a finished step reads like every other finished step, not 'label done.'");
    }

    @Test
    void formatDnfLineShowsElapsedOnlyForSlowSteps() {
        var done = BuildProgress.StepProgress.done(null);
        assertFalse(stripAnsi(BuildProgress.formatDnfLine("x", done, 0, 900)).contains("s"),
                "a quick step shows no time");
        assertTrue(stripAnsi(BuildProgress.formatDnfLine("x", done, 0, 41_000)).endsWith("  41s"));
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;]*m", "");
    }

    @Test
    void transferShowsPercentRateAndTimeLeft() {
        long mb = 1024 * 1024;
        assertEquals("25%  100.0 MB / 400.0 MB  10.0 MB/s  30s left",
                BuildProgress.describeTransfer(100 * mb, 0, 400 * mb, 10_000_000_000L));
        // Without a Content-Length there is no percentage or estimate.
        assertEquals("100.0 MB  10.0 MB/s", BuildProgress.describeTransfer(100 * mb, 0, -1, 10_000_000_000L));
        // Within the first second the rate is not yet meaningful.
        assertEquals("0%  64.0 KB / 400.0 MB", BuildProgress.describeTransfer(64 * 1024, 0, 400 * mb, 200_000_000L));
        // A resumed download counts the bytes it started from in the percentage, not in the rate.
        assertEquals("75%  300.0 MB / 400.0 MB  10.0 MB/s  10s left",
                BuildProgress.describeTransfer(300 * mb, 200 * mb, 400 * mb, 10_000_000_000L));
    }

    @Test
    void durationsReadAtAGlance() {
        assertEquals("42s", BuildProgress.formatDuration(42_000_000_000L));
        assertEquals("5m 07s", BuildProgress.formatDuration(307_000_000_000L));
        assertEquals("1h 03m", BuildProgress.formatDuration(3_780_000_000_000L));
    }

    @Test
    void transferProgressDescribesEachPhase() {
        var progress = new BuildProgress.TransferProgress();
        assertEquals("connecting", progress.detail());
        progress.received(1024, 4096);
        assertTrue(progress.detail().startsWith("25%  1.0 KB / 4.0 KB"), progress.detail());
        assertTrue(progress.fetched());
        progress.retrying("HTTP 500 from example.com", 2, 3);
        assertEquals("HTTP 500 from example.com, retrying (attempt 2 of 3)", progress.detail());
        // Resumed where the body broke off: the percentage carries on from there.
        progress.received(1024, 4096);
        assertTrue(progress.detail().startsWith("25%  1.0 KB / 4.0 KB"), progress.detail());
        progress.verifying();
        assertEquals("verifying checksum", progress.detail());

        var cached = new BuildProgress.TransferProgress();
        cached.verifyingCached();
        assertEquals("verifying cached copy", cached.detail());
        assertFalse(cached.fetched());
    }
}
