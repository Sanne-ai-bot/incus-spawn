package dev.incusspawn.mcp;

import dev.incusspawn.util.ByteLines;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** The stdio transport: one JSON-RPC message per line, UTF-8, in both directions. */
final class StdioTransport implements McpTransport {

    private final ByteLines in;
    private final OutputStream out;

    StdioTransport(InputStream in, OutputStream out) {
        this.in = new ByteLines(in);
        this.out = out;
    }

    @Override
    public byte[] read() throws IOException {
        byte[] line;
        do {
            line = in.next();
        } while (line != null && ByteLines.isBlank(line));
        return line;
    }

    @Override
    public void send(String json) throws IOException {
        // Messages must not contain embedded newlines; Jackson's compact output never does.
        var bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
    }
}
