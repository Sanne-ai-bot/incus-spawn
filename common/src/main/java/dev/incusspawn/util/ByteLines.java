package dev.incusspawn.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lines of a stream as the bytes it sent, split as {@link java.io.BufferedReader#readLine} splits
 * them: on {@code \n}, {@code \r} or {@code \r\n}.
 * For a line another program wrote as JSON: decoding it to a {@code String} first would replace
 * invalid UTF-8 with U+FFFD, while {@code Jjq.parseForeign(byte[], ...)} refuses it and says where.
 */
public final class ByteLines implements AutoCloseable {

    private final InputStream in;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    /** The last line ended with {@code \r}: a {@code \n} that comes next belongs to it. */
    private boolean skipLf;

    public ByteLines(InputStream in) {
        this.in = new BufferedInputStream(in);
    }

    /** The next line, or null at the end of the stream. */
    public byte[] next() throws IOException {
        line.reset();
        int b = in.read();
        if (skipLf && b == '\n') b = in.read();
        skipLf = false;
        if (b == -1) return null;
        while (b != -1 && b != '\n' && b != '\r') {
            line.write(b);
            b = in.read();
        }
        // Not read ahead here: on a pipe that would wait for the client's next message.
        skipLf = b == '\r';
        return line.toByteArray();
    }

    /** Whether {@code line} holds nothing but JSON's insignificant whitespace. */
    public static boolean isBlank(byte[] line) {
        for (var b : line) {
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return false;
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
