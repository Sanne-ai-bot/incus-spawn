package dev.incusspawn.proxy;

import io.netty.channel.unix.Errors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A peer's reset or broken pipe on epoll or kqueue is a routine teardown, matched by its errno (#475, #2215):
 * the message is the host's {@code strerror}, which a non-English locale translates. The proxy
 * keeps its own errno numbers, since reading Netty's initializes {@link Errors}, which loads the
 * JNI library and fails on NIO; this pins them to Netty's where the native transport loads
 * (epoll on Linux, kqueue on macOS).
 */
class BenignConnectionErrorTest {

    @BeforeAll
    static void nativeTransportLoads() {
        ProxyTransport.vertx().close();
    }

    @Test
    void errnoNumbersAreNettys() {
        assertEquals(Errors.ERRNO_ECONNRESET_NEGATIVE, -MitmProxy.ECONNRESET);
        assertEquals(Errors.ERRNO_EPIPE_NEGATIVE, -MitmProxy.EPIPE);
    }

    @Test
    void aResetOrBrokenPipeOnEpollIsATeardown() {
        assertTrue(MitmProxy.isBenignConnectionError(
                new Errors.NativeIoException("readAddress", Errors.ERRNO_ECONNRESET_NEGATIVE)));
        assertTrue(MitmProxy.isBenignConnectionError(
                new Errors.NativeIoException("writeAddress", Errors.ERRNO_EPIPE_NEGATIVE)));
    }

    @Test
    void anotherErrnoOnEpollIsAFault() {
        assertFalse(MitmProxy.isBenignConnectionError(
                new Errors.NativeIoException("readAddress", Errors.ERRNO_EBADF_NEGATIVE)));
    }
}
