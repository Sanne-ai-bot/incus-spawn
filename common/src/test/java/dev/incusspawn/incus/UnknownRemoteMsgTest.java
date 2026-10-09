package dev.incusspawn.incus;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What isx says when an image names an Incus remote it cannot resolve. */
class UnknownRemoteMsgTest {

    @Test
    void onMacOsTheMessageNamesWhereRemotesAreReadNotAnIncusCommand() {
        // #1139: the macOS host has no 'incus' CLI to add a remote with (#939).
        var msg = IncusClient.unknownRemoteMsg("mine", "mine:fedora/44", true);
        assertTrue(msg.contains("Unknown Incus remote 'mine'"), msg);
        assertTrue(msg.contains(Environment.incusConfigCandidates().getFirst().toString()), msg);
        assertTrue(msg.contains("addr"), msg);
        // readIncusRemote defaults the protocol to simplestreams: only addr is required (#1205).
        assertFalse(msg.contains("addr and protocol"), msg);
        assertTrue(msg.contains("protocol defaults to simplestreams"), msg);
        assertTrue(msg.contains("'images'"), msg);
        assertFalse(msg.contains("incus remote"), msg);
    }

    @Test
    void onLinuxTheMessagePointsAtIncusRemoteAdd() {
        var msg = IncusClient.unknownRemoteMsg("mine", "mine:fedora/44", false);
        assertTrue(msg.contains("incus remote add mine <url>"), msg);
    }
}
