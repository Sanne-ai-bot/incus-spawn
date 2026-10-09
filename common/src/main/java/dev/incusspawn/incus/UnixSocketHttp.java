package dev.incusspawn.incus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * One HTTP request over a Unix socket to a peer that is not Incus, for callers outside this
 * package. It goes through {@link UnixSocketTransport}, so there is one client to keep right:
 * its watchdog bounds connect, write and read together, and its parser reports a response that
 * was cut off instead of returning a shorter one.
 */
public final class UnixSocketHttp {

    private UnixSocketHttp() {}

    /**
     * POSTs {@code json} to {@code path} on {@code socket} and returns the HTTP status.
     *
     * @param timeoutSeconds limit for the whole exchange
     * @param timeoutHint    what an unanswered request most likely means, ending the timeout's message
     */
    public static int postJson(Path socket, String path, String json, int timeoutSeconds,
                               String timeoutHint) throws IOException {
        return new UnixSocketTransport(socket.toString(), timeoutSeconds, timeoutHint)
                .request("POST", path, "application/json", Map.of(), json.getBytes(StandardCharsets.UTF_8))
                .statusCode();
    }
}
