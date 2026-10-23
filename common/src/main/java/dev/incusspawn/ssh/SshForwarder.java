package dev.incusspawn.ssh;

import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.OwnSession;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A loopback listener per instance that pipes each connection to the instance's sshd through the
 * Incus exec API, the transport of {@code isx ssh-proxy} (#1600). It is for SSH clients that dial
 * a host and port and ignore {@code ProxyCommand}, such as JetBrains Gateway, where the instance's
 * address cannot be reached from the host: on macOS it is inside the appliance VM, and TCP into
 * the VM subnet is avoided by design. On Linux they dial the bridge address and need none.
 *
 * <p>{@code isx ssh-forward <instance>} runs a {@link Server}, detached from whoever started it
 * ({@link #ensure}), so a Gateway session outlives the TUI, {@code isx run} and an MCP call. It
 * records its pid and port in {@code ssh/forward/<instance>} under the config directory, and is
 * reused while its port answers with an SSH banner. It lives as long as the instance: it exits
 * once the instance is no longer running, and {@link #stop} ends it on destroy.
 */
public final class SshForwarder {

    /** How often the forwarder checks that the instance still runs. */
    public static final Duration POLL = Duration.ofSeconds(10);
    static final Duration START_WAIT = Duration.ofSeconds(10);
    static final Duration BANNER_WAIT = Duration.ofSeconds(5);
    static final String COMMAND = "ssh-forward";

    private SshForwarder() {}

    /**
     * Whether a client that dials the instance's address cannot reach it from this host, so it
     * needs a forwarder: today, macOS.
     */
    public static boolean needed() {
        return Platform.isMacOS();
    }

    /** Starts a detached {@code isx ssh-forward}; a test runs a {@link Server} instead. */
    interface Launcher {
        /** @return the started process, so an early exit fails at once; null when there is none to watch */
        ProcessHandle launch(String instance) throws IOException;
    }

    /**
     * The port of a live forwarder for {@code instance}, after starting one if none answers. On a
     * host that {@link #needed needs} it, the instance's managed Host block points there too, so
     * a client resolving the instance name through {@code ~/.ssh/config} dials the forwarder.
     */
    public static int ensure(String instance) throws IOException {
        int port = ensure(instance, SshForwarder::launch, START_WAIT);
        SshKeyManager.addHostEntry(instance, "127.0.0.1", port);
        return port;
    }

    static int ensure(String instance, Launcher launcher, Duration wait) throws IOException {
        return ensure(instance, launcher, wait, p -> p.info().commandLine());
    }

    static int ensure(String instance, Launcher launcher, Duration wait,
                      Function<ProcessHandle, Optional<String>> commandLine) throws IOException {
        try (var lock = HostLock.acquire(Environment.lockDir().resolve("ssh-forward").resolve(instance + ".lock"),
                "starting the SSH forwarder for " + instance, msg -> {})) {
            var current = read(instance);
            if (current.isPresent() && isLive(instance, current.get(), commandLine)) return current.get().port();
            stop(instance);
            var process = launcher.launch(instance);
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                var started = read(instance);
                if (started.isPresent() && isAlive(started.get().pid())) return started.get().port();
                if (process != null && !process.isAlive()) break;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted waiting for the SSH forwarder for " + instance);
                }
            }
            throw new IOException("the SSH forwarder for " + instance + " did not start; see " + logFile(instance));
        } catch (HostLock.HostLockException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** End the forwarder for {@code instance}, if one runs, and forget its record and log. */
    public static void stop(String instance) {
        read(instance).flatMap(r -> ProcessHandle.of(r.pid()))
                .filter(p -> isForwarderOf(p, instance))
                .ifPresent(p -> {
                    p.destroy();
                    try {
                        p.onExit().get(2, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        p.destroyForcibly();
                    }
                });
        try {
            Files.deleteIfExists(recordFile(instance));
            Files.deleteIfExists(logFile(instance));
        } catch (IOException ignored) {}
    }

    /**
     * Whether {@code process} runs {@code isx ssh-forward <instance>}, so a pid the record names
     * and the system has since given to another process is never killed.
     */
    static boolean isForwarderOf(ProcessHandle process, String instance) {
        return process.info().commandLine().map(c -> isForwarderCommand(c, instance)).orElse(false);
    }

    private static boolean isForwarderCommand(String commandLine, String instance) {
        return commandLine.contains(" " + COMMAND + " ") && commandLine.endsWith(" " + instance);
    }

    private static ProcessHandle launch(String instance) throws IOException {
        Files.createDirectories(forwardDir());
        var log = logFile(instance).toFile();
        // Its own session: neither a hangup of the terminal that ran the TUI nor a Ctrl+C there
        // ends Gateway's connection. Not OwnSession.start, which stops the child when isx exits.
        return OwnSession.apply(new ProcessBuilder(SshKeyManager.resolveIsxPath(), COMMAND, instance))
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectError(ProcessBuilder.Redirect.appendTo(log))
                .start().toHandle();
    }

    // --- The record ---

    /** A forwarder's pid and the loopback port it listens on. */
    record Record(long pid, int port) {
        String format() { return pid + " " + port + "\n"; }

        static Optional<Record> parse(String text) {
            var parts = text.strip().split("\\s+");
            if (parts.length != 2) return Optional.empty();
            try {
                return Optional.of(new Record(Long.parseLong(parts[0]), Integer.parseInt(parts[1])));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    static Path forwardDir() {
        return Environment.sshDir().resolve("forward");
    }

    static Path recordFile(String instance) {
        return forwardDir().resolve(instance);
    }

    static Path logFile(String instance) {
        return forwardDir().resolve(instance + ".log");
    }

    static Optional<Record> read(String instance) {
        try {
            return Record.parse(Files.readString(recordFile(instance)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void write(String instance, Record record) throws IOException {
        Files.createDirectories(forwardDir());
        SshKeyManager.writeAtomically(recordFile(instance), record.format());
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * A record names a live forwarder when its process runs and is that forwarder. Only where the
     * system does not say what a process runs does an SSH banner on its port stand in: a pid reused
     * by another process, with another instance's forwarder on the port, is not this one's. Its
     * own forwarder is never replaced only because sshd is slow to answer: that would cut the
     * sessions it carries.
     */
    static boolean isLive(String instance, Record record, Function<ProcessHandle, Optional<String>> commandLine) {
        var process = ProcessHandle.of(record.pid()).filter(ProcessHandle::isAlive);
        if (process.isEmpty()) return false;
        return commandLine.apply(process.get())
                .map(c -> isForwarderCommand(c, instance))
                .orElseGet(() -> answersSsh(record.port()));
    }

    static boolean answersSsh(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            socket.setSoTimeout((int) BANNER_WAIT.toMillis());
            var banner = socket.getInputStream().readNBytes(7);
            return "SSH-2.0".equals(new String(banner, StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return false;
        }
    }

    // --- The forwarder itself ---

    /** Carries one connection's bytes to the instance's sshd and back, returning when either side ends. */
    public interface Transport {
        void pipe(InputStream fromClient, OutputStream toClient) throws Exception;
    }

    /** What {@code isx ssh-forward} runs: the loopback listener for one instance. */
    public static final class Server implements AutoCloseable {
        private final String instance;
        private final ServerSocket socket;
        private final Transport transport;
        private final Supplier<String> status;
        private final Duration poll;

        private Server(String instance, ServerSocket socket, Transport transport, Supplier<String> status,
                       Duration poll) {
            this.instance = instance;
            this.socket = socket;
            this.transport = transport;
            this.status = status;
            this.poll = poll;
        }

        /**
         * Listen on loopback, on {@code preferredPort} when it is free (0 for none), so a client
         * that accepted the host key for {@code [127.0.0.1]:<port>} keeps it across restarts.
         *
         * @param status the instance's Incus status; the server exits once it is not {@code Running}
         */
        public static Server bind(String instance, int preferredPort, Transport transport, Supplier<String> status,
                                  Duration poll) throws IOException {
            return new Server(instance, listen(preferredPort), transport, status, poll);
        }

        private static ServerSocket listen(int preferredPort) throws IOException {
            var loopback = InetAddress.getLoopbackAddress();
            if (preferredPort > 0) {
                try {
                    return new ServerSocket(preferredPort, 50, loopback);
                } catch (IOException taken) {
                    // Another process holds it: any port will do, at the cost of one host-key prompt.
                }
            }
            return new ServerSocket(0, 50, loopback);
        }

        public int port() {
            return socket.getLocalPort();
        }

        InetAddress address() {
            return socket.getInetAddress();
        }

        /**
         * Record this server and accept connections until the instance stops running. Returns why
         * it stopped.
         */
        public String serve() throws IOException {
            var record = new Record(ProcessHandle.current().pid(), port());
            write(instance, record);
            try {
                socket.setSoTimeout((int) Math.max(1, poll.toMillis()));
                int failedChecks = 0;
                long nextCheck = System.nanoTime() + poll.toNanos();
                while (true) {
                    try {
                        accept(socket.accept());
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        // One failed accept (too many open files, an aborted handshake) is not the end.
                        if (socket.isClosed()) throw e;
                        sleepQuietly(100);
                    }
                    long now = System.nanoTime();
                    if (now - nextCheck >= 0) {
                        nextCheck = now + poll.toNanos();
                        try {
                            var current = status.get();
                            failedChecks = 0;
                            if (!"Running".equalsIgnoreCase(current)) {
                                return instance + " is " + (current == null || current.isBlank() ? "gone" : current);
                            }
                        } catch (RuntimeException e) {
                            // A tunnel hiccup is not a stop; three checks in a row are.
                            if (++failedChecks >= 3) return "cannot read the status of " + instance + ": " + e.getMessage();
                        }
                    }
                }
            } finally {
                close();
                if (read(instance).filter(record::equals).isPresent()) {
                    Files.deleteIfExists(recordFile(instance));
                }
            }
        }

        private void accept(Socket client) {
            Thread.ofVirtual().start(() -> {
                try (client) {
                    client.setTcpNoDelay(true);
                    transport.pipe(client.getInputStream(), client.getOutputStream());
                } catch (Exception ignored) {
                    // The client or the exec went away: either way this connection is over.
                }
            });
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
