package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a bulk exec stream is framed: what {@code isx ssh-proxy} relays for scp, and a git pack
 * exchange (#1643). Each frame and each socket read has a fixed cost, so a stream cut into small
 * frames, or read a header byte at a time, spent a core of the host on a transfer.
 */
class ExecRelayFramingTest {

    private static final String NAME = "relay";

    @Test
    @Timeout(10)
    void bulkStdinGoesInLargeFrames() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).servesExecs();
        var stdin = new byte[1024 * 1024];
        int exit = daemon.client().execBidirectional(NAME, 0, 0, "/", new String[]{"nc", "localhost", "22"},
                new ByteArrayInputStream(stdin), OutputStream.nullOutputStream(), OutputStream.nullOutputStream());

        assertEquals(0, exit);
        var frames = daemon.execStdinFrames();
        assertEquals(stdin.length, frames.stream().mapToInt(Integer::intValue).sum(), "every byte is sent");
        assertTrue(frames.size() <= 16,
                () -> "1 MiB of stdin should take at most 16 frames, took " + frames.size() + " of " + frames.getFirst());
    }

    /** Counts the writes reaching the socket. */
    private static final class CountingOutput extends ByteArrayOutputStream {
        int writes;

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            writes++;
            super.write(b, off, len);
        }

        @Override
        public synchronized void write(int b) {
            writes++;
            super.write(b);
        }
    }

    @Test
    void maskedFrameIsOneWriteThatUnmasksToItsPayload() throws IOException {
        var data = new byte[70_010];
        new java.util.Random(1643).nextBytes(data);
        var buf = new byte[0];
        for (int length : new int[]{0, 1, 7, 8, 9, 125, 126, 127, 4096, 65535, 65536, 70_001}) {
            var out = new CountingOutput();
            buf = UnixSocketTransport.wsSendMasked(out, buf, 0x2, data, 9, length);
            assertEquals(1, out.writes, "a frame of " + length + " bytes is one write");
            assertArrayEquals(Arrays.copyOfRange(data, 9, 9 + length), unmask(out.toByteArray()),
                    "payload of " + length + " bytes");
        }
    }

    /** The payload of one masked client frame, unmasked byte by byte as RFC 6455 §5.3 says. */
    private static byte[] unmask(byte[] frame) {
        assertEquals(0x82, frame[0] & 0xFF, "FIN + binary");
        assertEquals(0x80, frame[1] & 0x80, "client frames are masked");
        long length = frame[1] & 0x7F;
        int pos = 2;
        if (length == 126) {
            length = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
            pos = 4;
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | (frame[2 + i] & 0xFF);
            pos = 10;
        }
        var mask = Arrays.copyOfRange(frame, pos, pos + 4);
        pos += 4;
        assertEquals(frame.length - pos, length, "length field");
        var payload = new byte[(int) length];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (frame[pos + i] ^ mask[i % 4]);
        return payload;
    }

    /** Counts the reads reaching the socket. */
    private static final class CountingInput extends ByteArrayInputStream {
        int reads;

        CountingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read() {
            reads++;
            return super.read();
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            reads++;
            return super.read(b, off, len);
        }
    }

    @Test
    void frameHeadersCostNoReadOfTheirOwn() throws IOException {
        var wire = new ByteArrayOutputStream();
        for (int i = 0; i < 1000; i++) {
            wire.write(0x82);
            wire.write(100);
            wire.writeBytes(new byte[100]);
        }
        var raw = new CountingInput(wire.toByteArray());
        var in = new UnixSocketTransport.SocketInput(raw);
        int frames = 0;
        while (UnixSocketTransport.wsReadFrame(in, p -> {}) != null) frames++;

        assertEquals(1000, frames);
        assertTrue(raw.reads <= 5, "102 KB of frames should take a few socket reads, took " + raw.reads);
    }

    @Test
    void aStreamOfLargerFramesGrowsTheReadAhead() throws IOException {
        var wire = new ByteArrayOutputStream();
        int frameBytes = 8 * 1024; // above the first buffer, below a full read-ahead
        for (int i = 0; i < 500; i++) {
            wire.write(0x82);
            wire.write(126);
            wire.write(frameBytes >> 8);
            wire.write(frameBytes & 0xFF);
            wire.writeBytes(new byte[frameBytes]);
        }
        var raw = new CountingInput(wire.toByteArray());
        var in = new UnixSocketTransport.SocketInput(raw);
        while (UnixSocketTransport.wsReadFrame(in, p -> {}) != null) {}

        // Stuck at the first 4 KiB it would take two or three reads a frame.
        int full = wire.size() / UnixSocketTransport.READ_BUFFER;
        assertTrue(raw.reads <= full + 4, "4 MB of 8 KiB frames should take about " + full
                + " socket reads, took " + raw.reads);
    }

    @Test
    @Timeout(10)
    void unreadBytesAreReportedWhileAReaderWaits() throws Exception {
        var release = new CountDownLatch(1);
        var parked = new CountDownLatch(1);
        var raw = new InputStream() {
            private boolean sent;

            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (!sent) {
                    sent = true;
                    b[off] = 1;
                    b[off + 1] = 2;
                    return 2;
                }
                parked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return -1;
            }

            @Override
            public int available() {
                return 0;
            }
        };
        var in = new UnixSocketTransport.SocketInput(raw);
        assertEquals(1, in.read());
        assertEquals(1, in.available(), "the byte read ahead is unread");
        assertEquals(2, in.read());

        var reader = Thread.ofVirtual().start(() -> {
            try {
                in.read();
            } catch (IOException ignored) {}
        });
        parked.await();
        // The output drain asks this while the reader is parked in read(): it must answer, not wait.
        assertEquals(0, in.available());
        release.countDown();
        reader.join();
    }
}
