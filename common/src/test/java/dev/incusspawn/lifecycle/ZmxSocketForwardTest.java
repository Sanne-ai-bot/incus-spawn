package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.OwnDirectory;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Host-side repair of the {@code zmx-sockets} disk device.  The device source
 * lives on a tmpfs that disappears on reboot/logout, and Incus refuses to
 * start an instance whose disk source is missing, so the repair has to happen
 * before every start — without slowing the common case down.
 */
class ZmxSocketForwardTest {

    private static final String DEVICE = "zmx-sockets";
    private static final String NAME = "demo";
    @TempDir
    Path zmxDir;

    private IncusClient incus;

    @BeforeEach
    void setUp() {
        assumeFalse(Platform.isMacOS(), "host socket sharing is Linux-only");
        incus = mock(IncusClient.class);
    }

    private Path containerDir() {
        return zmxDir.resolve("containers").resolve(NAME);
    }

    private Path symlink() {
        return zmxDir.resolve("isx-" + NAME);
    }

    /** An instance as {@code IncusClient.instanceMetadata} returns it. */
    private JqValue instanceWithZmxDevice(String source) {
        var device = JqObject.builder(3).put("type", "disk").put("source", source).put("required", "false").build();
        return JqObject.builder(1)
                .put("devices", JqObject.builder(1).put(DEVICE, device).build())
                .build();
    }

    /** An instance branched before the device was optional (#1044): no {@code required} key. */
    private JqValue legacyInstanceWithZmxDevice(String source) {
        var device = JqObject.builder(2).put("type", "disk").put("source", source).build();
        return JqObject.builder(1)
                .put("devices", JqObject.builder(1).put(DEVICE, device).build())
                .build();
    }

    @Test
    void ensureHostDirForStart_directoryPresent_touchesNothing() throws IOException {
        Files.createDirectories(containerDir());

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(containerDir().toAbsolutePath().toString()), RecordingOutput.FAIL_ON_WARN);

        // The healthy path must cost a single stat and no Incus call at all,
        // or every start pays for the repair.
        verifyNoInteractions(incus);
    }

    /**
     * A device added before #1044 is required, so a plain {@code incus start}
     * after a reboot fails on the wiped tmpfs.  The isx start that finds it
     * re-adds it as optional, from the metadata it already has.
     */
    @Test
    void ensureHostDirForStart_requiredDevice_upgradesToOptional() throws IOException {
        Files.createDirectories(containerDir());

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                legacyInstanceWithZmxDevice(containerDir().toAbsolutePath().toString()), RecordingOutput.FAIL_ON_WARN);

        verify(incus).devicesRemoveAll(NAME, List.of(DEVICE));
        assertTrue(deviceAddArgs().contains("required=false"));
    }

    @Test
    void ensureHostDirForStart_noZmxDevice_createsNothing() {
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir, JqObject.builder(0).build(), RecordingOutput.FAIL_ON_WARN);

        assertFalse(Files.exists(containerDir()),
                "instances without the device must not get a stray directory");
        assertFalse(Files.exists(symlink()));
        verifyNoInteractions(incus);
    }

    @Test
    void ensureHostDirForStart_wipedTmpfs_recreatesDirectoryAndSymlink() throws IOException {
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(containerDir().toAbsolutePath().toString()), RecordingOutput.FAIL_ON_WARN);

        assertTrue(Files.isDirectory(containerDir()));
        assertTrue(Files.isSymbolicLink(symlink()));
        assertEquals(Path.of("containers", NAME, "isx"), Files.readSymbolicLink(symlink()));
        assertEquals("source=" + containerDir().toAbsolutePath(), deviceAddArgs().get(0));
    }

    @Test
    void ensureHostDirForStart_staleSourceAfterRename_repointsDevice() {
        var oldDir = zmxDir.resolve("containers").resolve("previous-name");

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(oldDir.toString()), RecordingOutput.FAIL_ON_WARN);

        assertTrue(Files.isDirectory(containerDir()));
        verify(incus).devicesRemoveAll(NAME, List.of(DEVICE));
        assertEquals("source=" + containerDir().toAbsolutePath(), deviceAddArgs().get(0));
    }

    /**
     * The directory for this name exists but the device still points at the
     * one a rename left behind: Incus would fail start validation, so the
     * present directory must not be taken as proof of health.
     */
    @Test
    void ensureHostDirForStart_rightDirectoryWrongSource_repointsDevice() throws IOException {
        Files.createDirectories(containerDir());
        var oldDir = zmxDir.resolve("containers").resolve("previous-name");

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(oldDir.toString()), RecordingOutput.FAIL_ON_WARN);

        assertEquals("source=" + containerDir().toAbsolutePath(), deviceAddArgs().get(0));
    }

    /**
     * A stale source that still exists on disk — the old name was recreated
     * since — would otherwise let the instance start against another
     * instance's sockets.
     */
    @Test
    void ensureHostDirForStart_staleSourceStillOnDisk_repointsDevice() throws IOException {
        var oldDir = zmxDir.resolve("containers").resolve("previous-name");
        Files.createDirectories(oldDir);

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, zmxDir,
                instanceWithZmxDevice(oldDir.toString()), RecordingOutput.FAIL_ON_WARN);

        assertTrue(Files.isDirectory(containerDir()));
        assertEquals("source=" + containerDir().toAbsolutePath(), deviceAddArgs().get(0));
    }

    @Test
    void configure_addsDiskDeviceForContainerZmxDir() throws IOException {
        ZmxSocketForward.configure(incus, NAME, zmxDir, RecordingOutput.FAIL_ON_WARN);

        assertTrue(Files.isDirectory(containerDir()));
        assertEquals(Path.of("containers", NAME, "isx"), Files.readSymbolicLink(symlink()));
        verify(incus).devicesRemoveAll(NAME, List.of(DEVICE));
        assertEquals(
                List.of("source=" + containerDir().toAbsolutePath(),
                        "path=/home/agentuser/.zmx",
                        // The source sits on a tmpfs: a start that bypasses isx
                        // after a reboot must not fail on it (#1044).
                        "required=false",
                        "shift=true"),
                deviceAddArgs());
    }

    /**
     * The pre-start hook runs both repairs — a host-resource source that
     * vanished and the zmx directory — off a single instance fetch, since
     * either one failing start validation is what it exists to prevent.
     */
    @Test
    void prepareForStart_bothRepairsShareOneFetch() {
        // A name no real host directory can collide with: this call resolves
        // the zmx dir from the environment, unlike the tests above.
        var name = "wiring-" + java.util.UUID.randomUUID();
        var instance = JqObject.builder(1)
                .put("config", JqObject.builder(1).put(Metadata.HOST_RESOURCES, "").build())
                .build();
        when(incus.instanceMetadata(name)).thenReturn(instance);

        InstanceLifecycle.prepareForStart(incus, name);

        verify(incus).instanceMetadata(name);
        verifyNoMoreInteractions(incus);
    }

    private List<String> deviceAddArgs() {
        var args = ArgumentCaptor.forClass(String[].class);
        verify(incus).deviceAdd(eq(NAME), eq(DEVICE), eq("disk"), args.capture());
        return List.of(args.getValue());
    }

    /** The host dir resolves as zmx 0.6.0 and 0.8.1 resolve their socket dir, {@code $TMPDIR/zmx-<uid>} included. */
    @Test
    void hostZmxDirResolvesAsZmxDoes() {
        Supplier<String> uid = () -> "1000";
        var all = Map.of("ZMX_DIR", "/z", "XDG_RUNTIME_DIR", "/run/user/1000", "TMPDIR", "/tmp/td");
        assertEquals(Path.of("/z"), ZmxSocketForward.hostZmxDir(all::get, uid));
        var blankZmxDir = Map.of("ZMX_DIR", " ", "XDG_RUNTIME_DIR", "/run/user/1000", "TMPDIR", "/tmp/td");
        assertEquals(Path.of("/run/user/1000", "zmx"), ZmxSocketForward.hostZmxDir(blankZmxDir::get, uid));
        var tmpdirOnly = Map.of("TMPDIR", "/tmp/td");
        assertEquals(Path.of("/tmp/td", "zmx-1000"), ZmxSocketForward.hostZmxDir(tmpdirOnly::get, uid));
        assertEquals(Path.of("/tmp", "zmx-1000"), ZmxSocketForward.hostZmxDir(Map.<String, String>of()::get, uid));
    }

    /** macOS shares no zmx sockets, so it must not resolve the dir, which can fork {@code id -u}. */
    @Test
    void sharedHostZmxDirResolvesNothingOnMacOS() {
        assertNull(ZmxSocketForward.sharedHostZmxDir(true, () -> fail("host zmx dir resolved on macOS")));
        assertEquals(zmxDir, ZmxSocketForward.sharedHostZmxDir(false, () -> zmxDir));
    }

    /**
     * zmx writes {@code logs/} under its dir, the shared directory, after any
     * session, so cleanup has to remove it recursively, and through no
     * symlink a guest left there (#1772).
     */
    @Test
    void cleanupRemovesTheSessionLogsWithoutFollowingSymlinks() throws IOException {
        var outside = Files.createDirectories(zmxDir.resolve("outside"));
        var kept = Files.writeString(outside.resolve("kept"), "x");
        var logs = Files.createDirectories(containerDir().resolve("logs"));
        Files.writeString(logs.resolve("zmx.log"), "log");
        Files.writeString(logs.resolve("isx.log"), "log");
        Files.createSymbolicLink(containerDir().resolve("escape"), outside);
        Files.createSymbolicLink(symlink(), Path.of("containers", NAME, "isx"));

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, zmxDir, out);

        assertEquals(List.of(), out.warnings());
        assertFalse(Files.exists(containerDir(), LinkOption.NOFOLLOW_LINKS), "container dir left behind");
        assertFalse(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS), "session symlink left behind");
        assertTrue(Files.exists(kept), "cleanup followed a symlink out of the container dir");
    }

    /** A user's ZMX_DIR may be a link of theirs, and cleanup still goes through it. */
    @Test
    void cleanupGoesThroughTheUsersOwnLink(@TempDir Path elsewhere) throws IOException {
        var logs = Files.createDirectories(containerDir().resolve("logs"));
        Files.writeString(logs.resolve("zmx.log"), "log");
        var link = Files.createSymbolicLink(elsewhere.resolve("zmx"), zmxDir);

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, link, out);

        assertEquals(List.of(), out.warnings());
        assertFalse(Files.exists(containerDir()), "container dir left behind");
    }

    /**
     * A {@code containers} that resolves somewhere else is not walked: on the
     * {@code /tmp} fallback another user can have planted it, and a link of
     * the user's own is not something isx made.
     */
    @Test
    void cleanupDoesNotWalkAContainersLink(@TempDir Path elsewhere) throws IOException {
        var victim = Files.createDirectories(elsewhere.resolve(NAME));
        var kept = Files.writeString(victim.resolve("kept"), "x");
        Files.createSymbolicLink(zmxDir.resolve("containers"), elsewhere);

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, zmxDir, out);

        assertTrue(Files.exists(kept), "cleanup deleted through a containers link");
        assertTrue(out.warnings().getFirst().contains("which is a link (to "), out.warnings().toString());
    }

    /**
     * A zmx dir others can write to is not walked either: they could swap
     * {@code containers} for a link between the check and the delete.  The
     * session link still goes: the dir is the user's, so nobody else can put
     * a link in its way.
     */
    @Test
    void cleanupDoesNotWalkAZmxDirOthersCanWrite() throws IOException {
        var logs = Files.createDirectories(containerDir().resolve("logs"));
        Files.writeString(logs.resolve("zmx.log"), "log");
        Files.createSymbolicLink(symlink(), Path.of("containers", NAME, "isx"));
        Files.setPosixFilePermissions(zmxDir, PosixFilePermissions.fromString("rwxrwxrwx"));

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, zmxDir, out);

        assertTrue(Files.exists(logs.resolve("zmx.log")), "cleanup walked a zmx dir others can write to");
        assertFalse(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS), "session symlink left behind");
        assertTrue(out.warnings().getFirst().contains("which others can write to"), out.warnings().toString());
    }

    /** An instance that never shared zmx sockets has nothing to warn of, whatever the dir is like. */
    @Test
    void cleanupSaysNothingOfAnInstanceWithoutZmxSessions() throws IOException {
        Files.createDirectories(zmxDir.resolve("containers").resolve("other"));
        Files.setPosixFilePermissions(zmxDir, PosixFilePermissions.fromString("rwxrwxrwx"));

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, zmxDir, out);

        assertEquals(List.of(), out.warnings());
    }

    /** Nor a {@code containers} others can write to, where they could swap the instance's dir for a link. */
    @Test
    void cleanupDoesNotWalkAContainersDirOthersCanWrite() throws IOException {
        var logs = Files.createDirectories(containerDir().resolve("logs"));
        Files.writeString(logs.resolve("zmx.log"), "log");
        Files.setPosixFilePermissions(zmxDir.resolve("containers"), PosixFilePermissions.fromString("rwxrwxrwx"));

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, zmxDir, out);

        assertTrue(Files.exists(logs.resolve("zmx.log")), "cleanup walked a containers dir others can write to");
        assertTrue(out.warnings().getFirst().contains("which others can write to"), out.warnings().toString());
    }

    /**
     * Ownership is checked against the uid isx runs as, never a guess: a uid
     * that cannot be read walks nothing, and a dir of another uid's (as
     * {@code /tmp/zmx-1000} would be, named by {@code id -u}'s fallback) is refused.
     */
    @Test
    void cleanupWalksNothingWithoutTheProcessUid() throws IOException {
        var logs = Files.createDirectories(containerDir().resolve("logs"));
        Files.writeString(logs.resolve("zmx.log"), "log");
        Files.createSymbolicLink(symlink(), Path.of("containers", NAME, "isx"));

        var unreadable = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, zmxDir, unreadable, () -> { throw new IOException("no /proc"); });
        var another = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, zmxDir, another, () -> OwnDirectory.processUid() + 1);

        assertTrue(Files.exists(logs.resolve("zmx.log")), "cleanup walked a dir without knowing whose it is");
        assertTrue(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS), "session symlink removed from another uid's dir");
        assertTrue(unreadable.warnings().getFirst().contains("cannot tell which uid"), unreadable.warnings().toString());
        assertTrue(another.warnings().getFirst().contains("which belongs to uid"), another.warnings().toString());
    }
}
