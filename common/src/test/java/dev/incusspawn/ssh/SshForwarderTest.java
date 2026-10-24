package dev.incusspawn.ssh;

import dev.incusspawn.Environment;
import dev.incusspawn.Warnings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The loopback forwarder Gateway dials where the instance's address cannot be reached (#1600). */
@Timeout(30)
class SshForwarderTest {

    private static final String INSTANCE = "dev-1";
    private static final Duration FAST = Duration.ofMillis(20);
    private static final String HOST_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHostKeyOfTheInstance";

    /** Stands in for sshd behind {@code nc localhost 22}: a banner, then every byte echoed. */
    private static final SshForwarder.Transport SSHD = (in, out) -> {
        out.write("SSH-2.0-fake\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        in.transferTo(out);
    };

    @TempDir
    Path home;

    private String originalHome;
    private final List<SshForwarder.Server> servers = new ArrayList<>();

    @BeforeEach
    void isolateHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() throws IOException {
        for (var server : servers) server.close();
        System.setProperty("user.home", originalHome);
    }

    private SshForwarder.Server bind(int preferredPort, SshForwarder.Transport transport,
                                     java.util.function.Supplier<String> status) throws IOException {
        return bind(preferredPort, transport, status, () -> HOST_KEY);
    }

    private SshForwarder.Server bind(int preferredPort, SshForwarder.Transport transport,
                                     java.util.function.Supplier<String> status,
                                     java.util.function.Supplier<String> hostKey) throws IOException {
        var server = SshForwarder.Server.bind(INSTANCE, preferredPort, transport, status, hostKey, FAST);
        servers.add(server);
        return server;
    }

    /**
     * Serve in the background, as the detached process would, once its record is on disk. A server
     * that ends at its first status check (the instance is gone) may write and remove its record
     * between two looks, so it having finished counts as started.
     */
    private CompletableFuture<String> serve(SshForwarder.Server server) throws Exception {
        var done = CompletableFuture.supplyAsync(() -> {
            try {
                return server.serve();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (SshForwarder.read(INSTANCE).isEmpty() && !done.isDone()) {
            if (System.nanoTime() > deadline) fail("the server never wrote its record");
            Thread.sleep(5);
        }
        return done;
    }

    @Test
    void bytesRoundTripThroughTheForwarderOnLoopback() throws Exception {
        var server = bind(0, SSHD, () -> "Running");
        serve(server);

        assertTrue(server.address().isLoopbackAddress(), "bound to " + server.address());
        try (var client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            client.setSoTimeout(5000);
            var in = client.getInputStream();
            assertEquals("SSH-2.0-fake\r\n", new String(in.readNBytes(14), StandardCharsets.US_ASCII));
            client.getOutputStream().write("hello sshd".getBytes(StandardCharsets.US_ASCII));
            assertEquals("hello sshd", new String(in.readNBytes(10), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void theForwarderExitsOnceTheInstanceIsNotRunningAndForgetsItsRecord() throws Exception {
        var status = new AtomicReference<>("Running");
        var done = serve(bind(0, SSHD, status::get));
        assertTrue(Files.exists(SshForwarder.recordFile(INSTANCE)));

        status.set("Stopped");

        assertEquals(INSTANCE + " is Stopped", done.get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(SshForwarder.recordFile(INSTANCE)), "a stopped forwarder must not look live");
    }

    @Test
    void aDeletedInstanceEndsTheForwarderToo() throws Exception {
        var done = serve(bind(0, SSHD, () -> ""));

        assertEquals(INSTANCE + " is gone", done.get(5, TimeUnit.SECONDS));
    }

    /**
     * Gateway reopens a project from its own recent list, so a forwarder that exited while nobody
     * was connected would leave it dialling a dead port with no hint to press F9 (#1600).
     */
    @Test
    void aForwarderWithNoConnectionLivesAsLongAsTheInstanceRuns() throws Exception {
        var checks = new CountDownLatch(10);
        var server = bind(0, SSHD, () -> {
            checks.countDown();
            return "Running";
        });
        var done = serve(server);

        assertTrue(checks.await(5, TimeUnit.SECONDS), "the forwarder stopped checking the instance");
        assertFalse(done.isDone(), "exited with the instance running: " + (done.isDone() ? done.get() : ""));
        try (var client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            client.setSoTimeout(5000);
            assertEquals("SSH-2.0-fake\r\n", new String(client.getInputStream().readNBytes(14), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void theForwarderKeepsItsPortAcrossRestartsWhileItIsFree() throws Exception {
        int port;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        assertEquals(port, bind(port, SSHD, () -> "Running").port());

        var other = bind(port, SSHD, () -> "Running");
        assertNotEquals(port, other.port(), "a taken port falls back to another");
    }

    @Test
    void aLiveForwarderIsReused() throws Exception {
        var server = bind(0, SSHD, () -> "Running");
        serve(server);

        // It runs in this JVM, whose command line names no forwarder: as where the system does not say.
        int port = SshForwarder.ensure(INSTANCE, name -> fail("started a second forwarder"), Duration.ofSeconds(5),
                p -> Optional.empty());

        assertEquals(server.port(), port);
    }

    /** A pid reused by another process, with another instance's forwarder on the port (#1600). */
    @Test
    void aRecordWhoseProcessRunsSomethingElseIsReplacedEvenIfItsPortAnswersSsh() throws Exception {
        var otherInstances = bind(0, SSHD, () -> "Running");
        serve(otherInstances);
        writeRecord(ProcessHandle.current().pid(), otherInstances.port());

        int port = SshForwarder.ensure(INSTANCE, startsAForwarder(), Duration.ofSeconds(5),
                p -> Optional.of("/usr/bin/java -jar something-else.jar"));

        assertNotEquals(otherInstances.port(), port);
    }

    @Test
    void aRecordWhoseProcessIsGoneIsReplaced() throws Exception {
        var dead = new ProcessBuilder("true").start();
        dead.waitFor();
        writeRecord(dead.pid(), 1);

        int port = SshForwarder.ensure(INSTANCE, startsAForwarder(), Duration.ofSeconds(5));

        assertEquals(servers.getLast().port(), port);
    }

    @Test
    void aRecordWhosePortGivesNoSshBannerIsReplaced() throws Exception {
        // The pid is alive (this JVM), but what answers on the port is not a forwarder.
        int silent;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            silent = probe.getLocalPort();
        }
        writeRecord(ProcessHandle.current().pid(), silent);

        int port = SshForwarder.ensure(INSTANCE, startsAForwarder(), Duration.ofSeconds(5));

        assertNotEquals(silent, port);
        assertEquals(servers.getLast().port(), port);
        assertTrue(ProcessHandle.current().isAlive(), "replacing a stale record kills nothing that is not a forwarder");
    }

    @Test
    void aForwarderThatNeverStartsIsReportedWithItsLog() {
        var e = assertThrows(IOException.class,
                () -> SshForwarder.ensure(INSTANCE, name -> null, Duration.ofMillis(200)));

        assertTrue(e.getMessage().contains(SshForwarder.logFile(INSTANCE).toString()), e.getMessage());
    }

    @Test
    void aForwarderThatExitsAtOnceFailsWithoutWaitingOutTheStart() throws Exception {
        var exited = new ProcessBuilder("true").start();
        exited.waitFor();
        long start = System.nanoTime();

        assertThrows(IOException.class, () -> SshForwarder.ensure(INSTANCE, name -> exited.toHandle(), Duration.ofSeconds(20)));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "waited out the start");
    }

    @Test
    void onlyTheForwarderOfThatInstanceIsEverStopped() {
        assertFalse(SshForwarder.isForwarderOf(ProcessHandle.current(), INSTANCE));
    }

    @Test
    void stoppingForgetsTheRecordAndTheLog() throws IOException {
        writeRecord(Long.MAX_VALUE, 40222);
        Files.writeString(SshForwarder.logFile(INSTANCE), "Forwarding 127.0.0.1:40222 to dev-1:22\n");

        SshForwarder.stop(INSTANCE);

        assertFalse(Files.exists(SshForwarder.recordFile(INSTANCE)));
        assertFalse(Files.exists(SshForwarder.logFile(INSTANCE)), "a destroyed instance must not leave its log behind");
    }

    @Test
    void recordsParseOnlyWhatTheForwarderWrites() {
        assertEquals(new SshForwarder.Record(42, 40222), SshForwarder.Record.parse("42 40222\n").orElseThrow());
        assertTrue(SshForwarder.Record.parse("").isEmpty());
        assertTrue(SshForwarder.Record.parse("42").isEmpty());
        assertTrue(SshForwarder.Record.parse("x 40222").isEmpty());
    }

    /**
     * Gateway takes the managed key from the Host block, so a block that still names another port
     * would open it where it cannot log in; the failure goes to the warning channel, not stderr,
     * because the TUI reaches it (#1600).
     */
    @Test
    void aHostBlockThatCannotBeWrittenFailsTheActionWithAWarning() throws Exception {
        Files.createDirectories(Environment.sshConfigFile());
        var warnings = new ArrayList<String>();

        IOException e;
        try (var ignored = Warnings.redirect(new Warnings.Channel(warnings::add))) {
            e = assertThrows(IOException.class, () -> SshForwarder.pointHostBlockAt(INSTANCE, 2222));
        }

        assertTrue(e.getMessage().contains("127.0.0.1:2222"), e.getMessage());
        assertTrue(warnings.getLast().startsWith("failed to update SSH config"), warnings.toString());
    }

    /**
     * A record means {@link SshForwarder#ensure} returns and Gateway dials, so the key it is to
     * verify must be pinned by then (#1600).
     */
    @Test
    void theHostKeyIsPinnedBeforeTheRecordIsWritten() throws Exception {
        var recordedFirst = new AtomicReference<Boolean>();
        var server = bind(0, SSHD, () -> "Running", () -> {
            recordedFirst.set(Files.exists(SshForwarder.recordFile(INSTANCE)));
            return HOST_KEY;
        });
        serve(server);

        assertEquals(Boolean.FALSE, recordedFirst.get(), "the record was written before the key was read");
        assertEquals(List.of("[127.0.0.1]:" + server.port() + " " + HOST_KEY + " isx-forward:" + INSTANCE),
                Files.readAllLines(Environment.sshKnownHostsFile()));
    }

    @Test
    void aPinnedKeyMakesTheHostBlockCheckItStrictly() throws Exception {
        var server = bind(0, SSHD, () -> "Running");
        serve(server);
        var warnings = new ArrayList<String>();

        try (var ignored = Warnings.redirect(new Warnings.Channel(warnings::add))) {
            SshForwarder.pointHostBlockAt(INSTANCE, server.port());
        }

        var block = Files.readString(Environment.sshConfigFile());
        assertTrue(block.contains("StrictHostKeyChecking yes"), block);
        assertTrue(block.contains("UserKnownHostsFile \"" + Environment.sshKnownHostsFile() + "\""), block);
        assertEquals(List.of(), warnings);
    }

    /** A strict block must never lock the user out of an instance whose key isx does not hold. */
    @Test
    void aHostKeyThatCannotBeReadStillServesBehindTodaysBlockAndSaysSo() throws Exception {
        var server = bind(0, SSHD, () -> "Running", () -> null);
        serve(server);
        var warnings = new ArrayList<String>();

        try (var ignored = Warnings.redirect(new Warnings.Channel(warnings::add))) {
            SshForwarder.pointHostBlockAt(INSTANCE, server.port());
        }

        try (var client = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            client.setSoTimeout(5000);
            assertEquals("SSH-2.0-fake\r\n", new String(client.getInputStream().readNBytes(14), StandardCharsets.US_ASCII));
        }
        var block = Files.readString(Environment.sshConfigFile());
        assertTrue(block.contains("StrictHostKeyChecking no"), block);
        assertTrue(block.contains("UserKnownHostsFile /dev/null"), block);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("could not be pinned"), warnings.toString());
    }

    /** A key pinned for this port by an instance before is not this one's, so it is dropped, not kept. */
    @Test
    void aHostKeyThatCannotBeReadLeavesNoKeyForThatPort() throws Exception {
        var server = bind(0, SSHD, () -> "Running", () -> null);
        SshKeyManager.pinLoopbackHostKey("dev-0", server.port(), HOST_KEY);

        serve(server);

        assertEquals(List.of(), Files.readAllLines(Environment.sshKnownHostsFile()));
    }

    /** The file is the instance's to write: only an ed25519 key reaches known_hosts. */
    @Test
    void onlyAnEd25519KeyIsTakenFromTheInstance() {
        assertEquals(HOST_KEY, SshForwarder.hostKeyOf(HOST_KEY + " root@dev-1\n"));
        assertNull(SshForwarder.hostKeyOf(""));
        assertNull(SshForwarder.hostKeyOf("ssh-rsa AAAAB3NzaC1yc2E"));
        assertNull(SshForwarder.hostKeyOf("ssh-ed25519 not*base64"));
        assertNull(SshForwarder.hostKeyOf("ssh-ed25519"));
    }

    /** What the detached launch does, in this process: a forwarder that records itself. */
    private SshForwarder.Launcher startsAForwarder() {
        return name -> {
            try {
                serve(bind(0, SSHD, () -> "Running"));
                return null;
            } catch (Exception e) {
                throw new IOException(e);
            }
        };
    }

    private static void writeRecord(long pid, int port) throws IOException {
        Files.createDirectories(SshForwarder.forwardDir());
        Files.writeString(SshForwarder.recordFile(INSTANCE), new SshForwarder.Record(pid, port).format());
    }
}
