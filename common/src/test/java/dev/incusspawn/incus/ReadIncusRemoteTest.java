package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A remote in a config file isx found but could not use is named as such, not as unknown (#1221). */
class ReadIncusRemoteTest {

    @TempDir Path dir;

    private Path write(String name, String yaml) throws Exception {
        var file = dir.resolve(name);
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void aRemoteWithAnAddrResolves() throws Exception {
        var file = write("config.yml", "remotes:\n  mine:\n    addr: https://example.org\n");
        var problems = new ArrayList<String>();
        var remote = IncusClient.readIncusRemote("mine", List.of(file), problems);
        assertNotNull(remote);
        assertEquals("https://example.org", remote.addr());
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void aFileThatDoesNotParseIsNamedWithTheReason() throws Exception {
        var file = write("config.yml", "remotes:\n  mine:\n    addr: https://example.org\n   protocol: [\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("mine", List.of(file), problems));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith(file + ":2: "), problems.getFirst());
        assertFalse(problems.getFirst().contains("\n"), problems.getFirst());

        var msg = IncusClient.unusableRemoteMsg("mine", "mine:fedora/44", problems, false);
        assertTrue(msg.startsWith("Cannot resolve Incus remote 'mine' for image 'mine:fedora/44': " + file), msg);
        assertFalse(msg.contains("Unknown Incus remote"), msg);
        assertFalse(msg.contains(".."), msg);
        assertTrue(msg.endsWith("."), msg);
    }

    @Test
    void aRemoteWithoutAnAddrIsNamedWithTheKeysItHas() throws Exception {
        var file = write("config.yml", "remotes:\n  mine:\n    address: https://example.org\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("mine", List.of(file), problems));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains(file.toString()), problems.getFirst());
        assertTrue(problems.getFirst().contains("no 'addr'"), problems.getFirst());
        assertTrue(problems.getFirst().contains("address"), problems.getFirst());

        // macOS has no incus CLI (#939): the message says what the entry needs instead.
        var msg = IncusClient.unusableRemoteMsg("mine", "mine:fedora/44", problems, true);
        assertTrue(msg.contains("'remotes:' section"), msg);
        assertFalse(msg.contains("incus remote add"), msg);
    }

    @Test
    void aKeyWithAControlCharacterIsReportedOnOneLine() throws Exception {
        var file = write("config.yml", "remotes:\n  mine:\n    \"addr\\nx\": https://example.org\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("mine", List.of(file), problems));
        assertFalse(problems.getFirst().contains("\n"), problems.getFirst());
    }

    @Test
    void aWellKnownRemoteRedefinedWithoutAnAddrDoesNotFallBackToTheBuiltIn() throws Exception {
        // The user meant a mirror: quietly pulling from images.linuxcontainers.org would hide that.
        var file = write("config.yml", "remotes:\n  images:\n    address: https://mirror.example.org\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("images", List.of(file), problems));
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void aLaterFileThatDefinesTheRemoteWinsOverABrokenEarlierOne() throws Exception {
        var broken = write("a.yml", "remotes: [\n");
        var good = write("b.yml", "remotes:\n  mine:\n    addr: https://example.org\n");
        var remote = IncusClient.readIncusRemote("mine", List.of(broken, good), new ArrayList<>());
        assertNotNull(remote);
        assertEquals("https://example.org", remote.addr());
    }

    @Test
    void aFileThatDoesNotMentionTheRemoteIsNoProblem() throws Exception {
        var file = write("config.yml", "remotes:\n  other:\n    addr: https://example.org\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("mine", List.of(file, dir.resolve("absent.yml")), problems));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void theWellKnownImagesRemoteStillResolvesPastABrokenFile() throws Exception {
        var file = write("config.yml", "remotes: [\n");
        var remote = IncusClient.readIncusRemote("images", List.of(file), new ArrayList<>());
        assertNotNull(remote);
        assertEquals("https://images.linuxcontainers.org", remote.addr());
    }
}
