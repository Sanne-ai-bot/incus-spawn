package dev.incusspawn.proxy;

import dev.incusspawn.util.SystemTrust;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plain HTTP pass-through proxy for host-side API traffic capture.
 * <p>
 * Listens on localhost (no TLS), forwards to api.anthropic.com over TLS,
 * and logs every exchange via {@link ApiTrafficLog}. Use with
 * {@code ANTHROPIC_BASE_URL=http://localhost:PORT} to intercept Claude Code
 * traffic on the host for debugging.
 */
public class DumpProxy {

    private static final String UPSTREAM_HOST = "api.anthropic.com";

    private final int port;
    private final ApiTrafficLog log;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    // One per proxy, so exchanges resume TLS sessions instead of re-reading the trust store
    private final SSLSocketFactory upstreamTls = SystemTrust.sslContext().getSocketFactory();

    public DumpProxy(int port, ApiTrafficLog log) {
        this.port = port;
        this.log = log;
    }

    public void start() throws IOException {
        var server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        System.out.println("API dump proxy listening on http://localhost:" + port);
        System.out.println("Logs: " + log.logDir());
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  ANTHROPIC_BASE_URL=http://localhost:" + port + " claude");
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();

        while (true) {
            try {
                var client = server.accept();
                executor.submit(() -> handleConnection(client));
            } catch (IOException e) {
                System.err.println("Accept error: " + e.getMessage());
            }
        }
    }

    private void handleConnection(Socket client) {
        try (client) {
            client.setSoTimeout(120_000);
            var clientIn = client.getInputStream();
            var clientOut = client.getOutputStream();

            while (true) {
                var request = HttpMessage.readRequest(clientIn);
                if (request == null) return;

                var bodyBytes = request.readRequestBody(clientIn);
                var originalDump = request.dump();

                // Rewrite Host to upstream (client sends Host: localhost:PORT)
                request.setHeader("Host", UPSTREAM_HOST);
                request.setHeader("Connection", "close");

                handleExchange(request, bodyBytes, originalDump, clientOut);
            }
        } catch (java.net.SocketTimeoutException e) {
            // Keep-alive timeout
        } catch (IOException e) {
            // Connection closed
        } catch (Exception e) {
            System.err.println("Dump proxy error: " + e.getMessage());
        }
    }

    /** A handshaken TLS socket to {@code host}, whose certificate must be for that name, not only trusted. */
    static SSLSocket openUpstream(SSLSocketFactory tls, String host, int port) throws IOException {
        var socket = (SSLSocket) tls.createSocket(host, port);
        try {
            var params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.setSoTimeout(300_000);
            socket.startHandshake();
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private void handleExchange(HttpMessage request, byte[] bodyBytes,
                                String originalDump, OutputStream clientOut) throws Exception {
        var upstreamSocket = openUpstream(upstreamTls, UPSTREAM_HOST, 443);

        try (upstreamSocket) {
            var upstreamOut = upstreamSocket.getOutputStream();
            var upstreamIn = upstreamSocket.getInputStream();

            request.writeTo(upstreamOut);
            if (bodyBytes != null && bodyBytes.length > 0) {
                upstreamOut.write(bodyBytes);
                upstreamOut.flush();
            }

            var response = HttpMessage.readResponse(upstreamIn);
            if (response == null) return;
            response.removeHeader("Connection");

            var responseBody = ApiTrafficLog.captureSmallResponseBody(response, upstreamIn);

            log.logExchange(
                    originalDump, bodyBytes,
                    null, null,
                    response.dump(), responseBody);

            response.writeTo(clientOut);
            if (responseBody != null) {
                clientOut.write(responseBody);
                clientOut.flush();
            } else {
                response.relayResponseBody(upstreamIn, clientOut);
            }
        }
    }
}
