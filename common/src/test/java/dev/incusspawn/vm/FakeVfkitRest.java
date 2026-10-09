package dev.incusspawn.vm;

import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * vfkit's REST API as isx meets it since #1189: HTTP on a Unix socket. It records every request
 * as {@code <request line>|<body>}, runs {@code onRequest}, and answers with {@code reply}.
 */
final class FakeVfkitRest implements AutoCloseable {

    static final String ACCEPTED = "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n";

    final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();
    private final Path dir;
    private final Path socket;
    private final ServerSocketChannel server;
    private final Thread thread;

    FakeVfkitRest(String reply, Runnable onRequest) throws IOException {
        dir = Files.createTempDirectory("isxrest");
        socket = dir.resolve("r.sock"); // keep path short (macOS sun_path limit)
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        thread = Thread.ofVirtual().start(() -> serve(reply, onRequest));
    }

    String uri() {
        return "unix://" + socket;
    }

    private void serve(String reply, Runnable onRequest) {
        while (server.isOpen()) {
            try (var conn = server.accept()) {
                var in = Channels.newInputStream(conn);
                var requestLine = readLine(in);
                int length = 0;
                for (var header = readLine(in); !header.isEmpty(); header = readLine(in)) {
                    if (header.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(header.substring(header.indexOf(':') + 1).strip());
                    }
                }
                requests.add(requestLine + "|" + new String(in.readNBytes(length), StandardCharsets.UTF_8));
                onRequest.run();
                var out = Channels.newOutputStream(conn);
                out.write(reply.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        var line = new StringBuilder();
        for (int b; (b = in.read()) != -1 && b != '\n'; ) {
            if (b != '\r') line.append((char) b);
        }
        return line.toString();
    }

    @Override
    public void close() throws IOException, InterruptedException {
        server.close();
        thread.join(2000);
        Files.deleteIfExists(socket);
        Files.deleteIfExists(dir);
    }
}
