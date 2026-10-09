package dev.incusspawn.vm;

import dev.incusspawn.incus.UnixSocketHttp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Client for vfkit's REST API, which {@link VmManager} uses to ask the guest to shut down.
 * <p>
 * vfkit listens on a Unix socket in the VM's state directory (#1189). A TCP port had to be
 * picked before vfkit could bind it: any process could take it in between, and vfkit exits when
 * its REST address is in use, so the VM did not start. A socket path belongs to this user and is
 * bound by vfkit itself; it also keeps the API, which can stop the VM, away from other local
 * users. {@code java.net.http.HttpClient} cannot connect to a Unix socket, so the request goes
 * through the transport Incus requests use ({@link UnixSocketHttp}); the {@code http://} form is
 * still understood for a VM that an older isx started and this one stops.
 */
final class VfkitRest {

    static final String UNIX_SCHEME = "unix://";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    // Over the socket this bounds the whole exchange, connect included: a connect to a Unix
    // socket succeeds or fails at once, so it needs no limit of its own.
    private static final int REQUEST_TIMEOUT_SECONDS = 5;

    private VfkitRest() {}

    /** The {@code --restful-uri} value, and the content of the rest-uri file, for a socket. */
    static String uriOf(Path socket) {
        return UNIX_SCHEME + socket;
    }

    /** POSTs {@code json} to {@code path} of the API at {@code restUri}; returns the HTTP status. */
    static int post(String restUri, String path, String json) throws IOException, InterruptedException {
        return post(restUri, path, json, REQUEST_TIMEOUT_SECONDS);
    }

    /** Package-private overload so tests need not wait out the real timeout. */
    static int post(String restUri, String path, String json, int timeoutSeconds)
            throws IOException, InterruptedException {
        if (restUri.startsWith(UNIX_SCHEME)) {
            var socket = Path.of(restUri.substring(UNIX_SCHEME.length()));
            return UnixSocketHttp.postJson(socket, path, json, timeoutSeconds,
                    "vfkit's REST API at " + socket + " did not answer");
        }
        var client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        var request = HttpRequest.newBuilder()
                .uri(URI.create(restUri + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
