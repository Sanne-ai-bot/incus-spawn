package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IncusApi.Keepalive} stops without touching a ping in flight. Interrupting it would
 * close the socket, and with it whatever output was still unread in it. Waiting for it would
 * hang the exec behind a stdin write that the guest stopped reading.
 */
class KeepaliveTest {

    /** A socket whose ping blocks until released, recording whether the pinging thread was interrupted. */
    private static final class Socket implements IncusTransport.WsConnection {
        final CountDownLatch pinging = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Boolean> interruptedDuringPing = new CompletableFuture<>();
        final AtomicInteger pings = new AtomicInteger();
        @Override public byte[] readPayload() { return null; }
        @Override public byte[] readMessage() { return null; }
        @Override public long millisSinceLastReceived() { return 0; }
        @Override public boolean hasUnread() { return false; }
        @Override public void sendData(byte[] data, int offset, int length) {}
        @Override public void sendPing() {
            pings.incrementAndGet();
            pinging.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            interruptedDuringPing.complete(Thread.currentThread().isInterrupted());
        }
        @Override public void sendClose() {}
        @Override public void close() {}
    }

    @Test
    @Timeout(10)
    void stoppingDuringAStuckPingReturnsAtOnceWithoutInterruptingIt() throws Exception {
        var socket = new Socket();
        var keepalive = new IncusApi.Keepalive(socket, 1);
        assertTrue(socket.pinging.await(5, TimeUnit.SECONDS));

        // The ping is stuck, as one queued behind a stdin write the guest stopped reading would be.
        CompletableFuture.runAsync(keepalive::stop).get(5, TimeUnit.SECONDS);
        socket.release.countDown();

        assertFalse(socket.interruptedDuringPing.get(5, TimeUnit.SECONDS), "the ping's write was interrupted");
        Thread.sleep(50);
        assertEquals(1, socket.pings.get(), "no ping may follow stop");
    }
}
