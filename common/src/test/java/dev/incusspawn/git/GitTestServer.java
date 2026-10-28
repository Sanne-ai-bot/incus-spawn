package dev.incusspawn.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Loopback remote transport for preparation tests; production never enables file:// for tests.
 * The server holds its listening socket and serves each connection with {@code git daemon --inetd}:
 * a port picked, released and then bound by a daemon could be taken in between by a concurrent
 * test's daemon or a client's ephemeral port, and its readiness probe would find that instead.
 */
public final class GitTestServer implements AutoCloseable {
    private final Path root;
    private final ServerSocket listener;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Set<Process> daemons = ConcurrentHashMap.newKeySet();

    public GitTestServer(Path root) throws Exception {
        this.root = root.toAbsolutePath();
        listener = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::accept);
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                var socket = listener.accept();
                connections.add(socket);
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        Process daemon = null;
        try (socket) {
            daemon = new ProcessBuilder("git", "daemon", "--inetd", "--export-all", "--enable=receive-pack",
                    "--base-path=" + root).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            daemons.add(daemon);
            var stdin = daemon.getOutputStream();
            var in = socket.getInputStream();
            Thread.ofVirtual().start(() -> { try (stdin) { pump(in, stdin); } catch (IOException ignored) {} });
            pump(daemon.getInputStream(), socket.getOutputStream());
            socket.shutdownOutput();
            daemon.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            // The client went away or the server is closing.
        } finally {
            if (daemon != null) { daemon.destroy(); daemons.remove(daemon); }
            connections.remove(socket);
        }
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        var buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) >= 0; ) {
            out.write(buffer, 0, n);
            out.flush();
        }
    }

    public String url(Path repo) { return "git://127.0.0.1:" + listener.getLocalPort() + "/" + root.relativize(repo.toAbsolutePath()); }

    @Override public void close() throws Exception {
        listener.close();
        for (var socket : connections) socket.close();
        for (var daemon : daemons) {
            daemon.descendants().forEach(ProcessHandle::destroy);
            daemon.destroy();
            if (!daemon.waitFor(5, TimeUnit.SECONDS)) daemon.destroyForcibly();
        }
    }
}
