package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * A guest command's stderr, relayed to the host's terminal a line at a time as text that cannot
 * drive it: whatever runs in the instance decides it, and the program that started isx passes it
 * through untouched (git for {@code isx git-remote-helper}, #1588; ssh for {@code isx ssh-proxy}).
 * Lines are decoded as UTF-8 whole, so a character whose bytes arrive apart stays intact.
 */
final class GuestStderr extends OutputStream {
    /** A line without a break is shown in pieces of this size rather than held without bound. */
    private static final int MAX_LINE = 64 * 1024;

    private final PrintStream out;
    private final Predicate<String> watched;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean seen;

    GuestStderr(PrintStream out) {
        this(out, text -> false);
    }

    /** Also notes whether any line, as the guest wrote it, matched {@code watched} ({@link #sawWatched}). */
    GuestStderr(PrintStream out, Predicate<String> watched) {
        this.out = out;
        this.watched = watched;
    }

    @Override
    public synchronized void write(int b) {
        // Cut a long line where a character starts, never inside one; past the longest
        // UTF-8 character, cut anyway, so a run of continuation bytes cannot grow it forever.
        if (line.size() >= MAX_LINE && ((b & 0xC0) != 0x80 || line.size() >= MAX_LINE + 4)) emit();
        line.write(b);
        if (b == '\n') emit();
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) write(b[i]);
    }

    @Override
    public synchronized void close() {
        if (line.size() > 0) emit();
        out.flush();
    }

    synchronized boolean sawWatched() {
        return seen;
    }

    private void emit() {
        var text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        // Matched before guestText, which keeps only a line's last carriage-return frame.
        if (watched.test(text)) seen = true;
        out.println(BuildOutput.guestText(text));
    }
}
