package dev.incusspawn.vm;

import com.sun.net.httpserver.HttpServer;
import dev.incusspawn.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VmManager#stop()} says whether the guest shut down or was killed (#1155). The script
 * that keeps a VM's data disk across an uninstall warns from it; before, a stop that ended in
 * SIGKILL looked the same as a clean one. That was every stop on a Mac until the guest's agent
 * could be asked to shut it down (#881), and still is for an appliance from before that.
 *
 * <p>A {@code sleep} named like qemu stands in for the hypervisor: a copy on Linux, where a process
 * is known by the file it runs, and a link on macOS, where a copied system binary is killed on
 * exec and a process is known by the path it was started as.
 */
class VmStopTest {

    /**
     * Not a {@code @TempDir}: the fake agent listens on {@link Environment#vmAgentSocket()} under
     * this home, and macOS allows a Unix socket path 104 bytes, fewer than its own temp
     * directory plus JUnit's leaves for that.
     */
    private Path tempHome;
    private String originalHome;
    private Process vm;
    private HttpServer rest;
    private ServerSocketChannel agent;
    private final AtomicReference<String> asked = new AtomicReference<>();

    @BeforeEach
    void runningVm() throws IOException {
        tempHome = Files.createTempDirectory(Path.of("/tmp"), "isxvm");
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        var qemu = tempHome.resolve("qemu-system-x86_64");
        if (OS.MAC.isCurrentOs()) {
            Files.createSymbolicLink(qemu, Path.of("/bin/sleep"));
        } else {
            Files.copy(Path.of("/bin/sleep").toRealPath(), qemu);
            qemu.toFile().setExecutable(true);
        }
        vm = new ProcessBuilder(qemu.toString(), "60").start();
        Files.createDirectories(Environment.vmStateDir());
        Files.writeString(Environment.vmPidFile(), Long.toString(vm.pid()));
        assertTrue(VmManager.isRunning(), "the stand-in must pass for a VM");
    }

    @AfterEach
    void restore() {
        if (rest != null) rest.stop(0);
        if (agent != null) try { agent.close(); } catch (IOException ignored) {}
        vm.destroyForcibly();
        System.setProperty("user.home", originalHome);
        try (var files = Files.walk(tempHome)) {  // does not follow the stand-in's link
            files.sorted(Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
        } catch (IOException ignored) {}
    }

    @Test
    void aGuestThatDoesNotShutDownIsReportedAsSignalled() {
        // No REST API to ask: what a guest ignoring vfkit's stop request comes to (#881)
        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
        assertFalse(vm.isAlive());
    }

    @Test
    void aGuestThatShutsDownWhenAskedIsReportedAsShutDown() throws IOException {
        rest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rest.createContext("/vm/state", exchange -> {
            vm.destroy();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        rest.start();
        Files.writeString(Environment.vmRestUriFile(), "http://127.0.0.1:" + rest.getAddress().getPort());

        assertEquals(VmManager.StopResult.SHUT_DOWN, VmManager.stop());
        assertFalse(vm.isAlive());
    }

    /** A guest agent that answers one {@code shutdown} with {@code reply}, then runs {@code then}. */
    private void agentAnswering(String reply, Runnable then) throws IOException {
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(Environment.vmAgentSocket().toString()));
        agent = server;
        Thread.ofVirtual().start(() -> {
            try (var conn = server.accept()) {
                var verb = new BufferedReader(Channels.newReader(conn, StandardCharsets.US_ASCII)).readLine();
                asked.set(verb);
                conn.write(ByteBuffer.wrap((reply + "\n").getBytes(StandardCharsets.US_ASCII)));
            } catch (IOException ignored) {
                return;
            }
            then.run();
        });
    }

    @Test
    void aGuestAskedThroughItsAgentShutsDownByItself() throws IOException {
        // No REST API: before #881 this guest was signalled at once. Its agent takes a
        // moment to bring it down, as rcK does, and stop() waits for that.
        agentAnswering("shutting down", () -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            vm.destroy();
        });

        assertEquals(VmManager.StopResult.SHUT_DOWN, VmManager.stop());
        assertEquals("shutdown", asked.get());
        assertFalse(vm.isAlive());
    }

    @Test
    void anApplianceWithoutTheVerbIsStoppedAsBefore() throws IOException {
        agentAnswering("error: unknown verb", () -> {});

        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
        assertEquals("shutdown", asked.get());
        assertFalse(vm.isAlive());
    }

    @Test
    void aGuestThatAgreesButNeverGoesDownIsSignalledAfterTheWait() throws IOException {
        var wait = VmManager.guestShutdownWait;
        VmManager.guestShutdownWait = Duration.ofMillis(300);
        try {
            agentAnswering("shutting down", () -> {});
            assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stop());
            assertFalse(vm.isAlive());
        } finally {
            VmManager.guestShutdownWait = wait;
        }
    }

    @Test
    void aVmWhoseDisksAreDeletedNextIsNotAskedToShutDown() throws IOException {
        agentAnswering("shutting down", () -> {});

        assertEquals(VmManager.StopResult.SIGNALLED, VmManager.stopToDelete());
        assertNull(asked.get(), "a clean shutdown of disks about to be deleted is time wasted");
    }

    /**
     * What vfkit is started with since #1189: its REST API on a Unix socket, so that no TCP port
     * is picked before vfkit binds it. A stop that could not speak to that socket would end every
     * guest with signals.
     */
    @Test
    void aGuestAskedOverVfkitsUnixSocketIsReportedAsShutDown() throws Exception {
        try (var vfkit = new FakeVfkitRest(FakeVfkitRest.ACCEPTED, () -> vm.destroy())) {
            Files.writeString(Environment.vmRestUriFile(), vfkit.uri());

            assertEquals(VmManager.StopResult.SHUT_DOWN, VmManager.stop());
            assertFalse(vm.isAlive());
            assertEquals(java.util.List.of("POST /vm/state HTTP/1.1|{\"state\":\"Stop\"}"), vfkit.requests);
        }
    }

    @Test
    void aVmThatIsNotRunningSaysSo() throws IOException {
        Files.delete(Environment.vmPidFile());
        assertEquals(VmManager.StopResult.NOT_RUNNING, VmManager.stop());
    }
}
