package dev.incusspawn.mcp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one gate for what {@code isx mcp} logs for the person who reads its client's log (#1703),
 * as {@code ProxyLog} is the proxy daemon's: on stderr, which {@link StdioGuard} leaves to the
 * client while stdout is the protocol. A message and its details are one block written with one
 * {@code println}, so another log line or anything else written to {@code System.err} cannot land
 * inside a stack trace; only its first line starts {@code isx mcp: }.
 */
final class McpLog {

    static final String PREFIX = "isx mcp: ";

    /** Where each block goes: stderr, read when written; {@link #capture} hands a test its lines. */
    private static volatile Consumer<String> sink = line -> System.err.println(line);

    private McpLog() {}

    /** {@code message}, then each of {@code details} on a line of its own under it, as one block. */
    static synchronized void log(String message, String... details) {
        var block = new StringBuilder(PREFIX).append(message);
        for (var detail : details) block.append('\n').append(detail);
        sink.accept(block.toString());
    }

    /** {@code failure}'s stack trace, its lines as they are, for a handler that threw. */
    static String trace(Throwable failure) {
        var text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString().stripTrailing();
    }

    /** For a test: the lines logged until it is closed, in place of stderr. */
    static Captured capture() {
        return new Captured();
    }

    static final class Captured implements AutoCloseable {
        private final List<String> blocks = new ArrayList<>();
        private final Consumer<String> previous = sink;

        private Captured() {
            sink = block -> {
                synchronized (blocks) {
                    blocks.add(block);
                }
            };
        }

        /** Each write, as it was written. */
        List<String> blocks() {
            synchronized (blocks) {
                return List.copyOf(blocks);
            }
        }

        /** The lines of every write, in order. */
        List<String> lines() {
            return blocks().stream().flatMap(String::lines).toList();
        }

        @Override
        public void close() {
            sink = previous;
        }
    }
}
