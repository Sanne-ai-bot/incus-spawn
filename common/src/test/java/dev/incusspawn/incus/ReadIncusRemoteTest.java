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
        assertEquals("simplestreams", remote.protocol());
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void anEmptyProtocolTakesTheDefault() throws Exception {
        // Incus rejects an empty protocol with an opaque error; the entry meant "no protocol".
        var file = write("config.yml", "remotes:\n  mine:\n    addr: https://example.org\n    protocol: ''\n");
        assertEquals("simplestreams", IncusClient.readIncusRemote("mine", List.of(file), new ArrayList<>()).protocol());
    }

    @Test
    void aRemoteNamingItsProtocolKeepsIt() throws Exception {
        var file = write("config.yml", "remotes:\n  mine:\n    addr: https://example.org\n    protocol: incus\n");
        assertEquals("incus", IncusClient.readIncusRemote("mine", List.of(file), new ArrayList<>()).protocol());
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
    void aBrokenFileStopsTheWellKnownFallback() throws Exception {
        // isx cannot see what the file says: it may define the mirror the user meant for 'images'.
        // The incus CLI refuses a config it cannot decode, too.
        var file = write("config.yml", "remotes:\n  images:\n    addr: https://mirror.example.org\n   protocol: [\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("images", List.of(file), problems));
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void theWellKnownImagesRemoteResolvesWhenNoFileDefinesIt() throws Exception {
        var file = write("config.yml", "remotes:\n  other:\n    addr: https://example.org\n");
        var remote = IncusClient.readIncusRemote("images", List.of(file, dir.resolve("absent.yml")), new ArrayList<>());
        assertNotNull(remote);
        assertEquals("https://images.linuxcontainers.org", remote.addr());
        assertEquals("simplestreams", remote.protocol());
    }

    @Test
    void anEntryThatIsNotAMappingSaysAMappingIsNeeded() throws Exception {
        var file = write("config.yml", "remotes:\n  mine: https://example.org\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("mine", List.of(file), problems));
        assertTrue(problems.getFirst().endsWith("defines remote 'mine' as a single value, where isx needs a mapping with an 'addr'"),
                problems.getFirst());
    }

    @Test
    void anAddrThatIsNotAValueIsNamedAsSuch() throws Exception {
        var file = write("config.yml", "remotes:\n  mine:\n    addr:\n      url: https://example.org\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("mine", List.of(file), problems));
        assertTrue(problems.getFirst().endsWith("with an 'addr' that is not a URL"), problems.getFirst());
    }

    @Test
    void aBlankOrNonStringAddrIsNotAServer() throws Exception {
        for (var addr : List.of("' '", "true", "42")) {
            var file = write("config.yml", "remotes:\n  mine:\n    addr: " + addr + "\n");
            var problems = new ArrayList<String>();
            assertNull(IncusClient.readIncusRemote("mine", List.of(file), problems), addr);
            assertEquals(1, problems.size(), problems.toString());
        }
    }

    @Test
    void anEmptyRemotesSectionIsNoProblem() throws Exception {
        var file = write("config.yml", "remotes:\n");
        var problems = new ArrayList<String>();
        assertNotNull(IncusClient.readIncusRemote("images", List.of(file), problems));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void aRemotesThatIsNotAMappingStopsTheWellKnownFallback() throws Exception {
        // A list may hold the mirror the user meant for 'images'.
        var file = write("config.yml", "remotes:\n  - name: images\n    addr: https://mirror.example.org\n");
        var problems = new ArrayList<String>();
        assertNull(IncusClient.readIncusRemote("images", List.of(file), problems));
        assertTrue(problems.getFirst().endsWith("has a 'remotes:' that is not a mapping of remote names"), problems.getFirst());
    }

    @Test
    void everyProblemLosesItsTrailingPeriodInTheMessage() {
        var msg = IncusClient.unusableRemoteMsg("mine", "mine:x", List.of("/a: one.", "/b: two."), false);
        assertEquals("Cannot resolve Incus remote 'mine' for image 'mine:x': /a: one; /b: two.", msg);
    }
}
