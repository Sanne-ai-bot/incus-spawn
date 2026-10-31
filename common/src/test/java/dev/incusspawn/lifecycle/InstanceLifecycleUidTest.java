package dev.incusspawn.lifecycle;

import dev.incusspawn.Platform;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The host uid is read on every start and {@code isx shell} of an instance
 * with zmx when neither {@code ZMX_DIR} nor {@code XDG_RUNTIME_DIR} is set,
 * so on Linux it must not cost a process (#1804).
 */
class InstanceLifecycleUidTest {

    @TempDir
    Path tmp;

    @Test
    void readsTheUidWithoutStartingAProcess() throws Exception {
        assumeFalse(Platform.isMacOS(), "macOS has no /proc to read the uid from");
        String uid;
        var dump = tmp.resolve("uid.jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.ProcessStart");
            recording.start();
            uid = InstanceLifecycle.getUid();
            recording.stop();
            recording.dump(dump);
        }
        var started = RecordingFile.readAllEvents(dump).stream()
                .filter(e -> e.getEventType().getName().equals("jdk.ProcessStart"))
                // Only this thread's: another test may start a process meanwhile
                .filter(e -> e.getThread("eventThread") != null
                        && e.getThread("eventThread").getJavaThreadId() == Thread.currentThread().threadId())
                .map(e -> e.getString("command"))
                .toList();
        assertTrue(started.isEmpty(), "getUid() started " + started);
        var id = new ProcessBuilder("id", "-u").start();
        var expected = new String(id.getInputStream().readAllBytes()).strip();
        assertEquals(0, id.waitFor());
        assertEquals(expected, uid);
    }

    @Test
    void takesTheEffectiveUidFromTheStatusFile() throws Exception {
        var status = tmp.resolve("status");
        Files.writeString(status, "Name:\tisx\nUmask:\t0022\nUid:\t1000\t1001\t1002\t1003\nGid:\t1000\t1000\t1000\t1000\n");
        assertEquals("1001", InstanceLifecycle.effectiveUid(status));
    }

    @Test
    void anUnreadableStatusFileGivesNoUid() throws Exception {
        assertNull(InstanceLifecycle.effectiveUid(tmp.resolve("missing")));
        var garbled = tmp.resolve("garbled");
        Files.writeString(garbled, "Uid:\t1000\n");
        assertNull(InstanceLifecycle.effectiveUid(garbled));
    }
}
