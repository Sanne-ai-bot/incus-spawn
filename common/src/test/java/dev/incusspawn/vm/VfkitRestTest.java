package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * isx reaches vfkit's REST API over a Unix socket (#1189), where {@code HttpClient} cannot go:
 * the request goes through the Incus transport, so what vfkit is sent and what isx makes of the
 * answer is checked here against a socket that speaks HTTP the way vfkit does.
 */
@Timeout(20)
class VfkitRestTest {

    @Test
    void aPostOverTheUnixSocketIsACompleteHttpRequestAndReturnsItsStatus() throws Exception {
        try (var vfkit = new FakeVfkitRest(FakeVfkitRest.ACCEPTED, () -> {})) {
            assertEquals(202, VfkitRest.post(vfkit.uri(), "/vm/state", "{\"state\":\"Stop\"}"));
            assertEquals(List.of("POST /vm/state HTTP/1.1|{\"state\":\"Stop\"}"), vfkit.requests);
        }
    }

    @Test
    void anAnswerThatIsNotHttpIsAnError() throws Exception {
        try (var vfkit = new FakeVfkitRest("nonsense\r\n", () -> {})) {
            assertThrows(IOException.class, () -> VfkitRest.post(vfkit.uri(), "/vm/state", "{}"));
        }
    }

    /**
     * A wedged vfkit must not hold up a stop, which waits for this call with the VM lock held:
     * the request gives up at its limit and says which socket stayed silent.
     */
    @Test
    void aSocketThatAcceptsAndNeverAnswersTimesOut() throws Exception {
        var dir = Files.createTempDirectory("isxrest"); // short: macOS limits a socket path
        var socket = dir.resolve("r.sock");
        // Bound and never accepted from: the connect and the request succeed, no answer comes.
        try (var silent = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            silent.bind(UnixDomainSocketAddress.of(socket));
            long start = System.nanoTime();
            var e = assertThrows(IOException.class,
                    () -> VfkitRest.post(VfkitRest.uriOf(socket), "/vm/state", "{}", 1));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(e.getMessage().contains("timed out after 1s"), e.getMessage());
            assertTrue(e.getMessage().contains("vfkit's REST API at " + socket), e.getMessage());
            assertTrue(elapsedMs < 4000, "bounded by the limit given (1s): " + elapsedMs + "ms");
        } finally {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void aSocketNobodyListensOnIsAnError() {
        assertThrows(IOException.class,
                () -> VfkitRest.post("unix:///nonexistent/isx-1189/r.sock", "/vm/state", "{}"));
    }
}
