package dev.incusspawn.git;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;

/** A loopback remote that accepts every connection and never answers: a stalled transfer. */
final class SilentRemote implements AutoCloseable {
    private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    private final CopyOnWriteArrayList<Socket> accepted = new CopyOnWriteArrayList<>();

    SilentRemote() throws IOException {
        Thread.ofPlatform().daemon().start(() -> {
            try { while (true) accepted.add(socket.accept()); } catch (IOException closed) {}
        });
    }

    String url(String scheme, String path) { return scheme + "://127.0.0.1:" + socket.getLocalPort() + "/" + path; }

    @Override public void close() throws IOException {
        socket.close();
        for (var connection : accepted) connection.close();
    }
}
