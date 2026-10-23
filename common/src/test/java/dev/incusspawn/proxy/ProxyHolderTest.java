package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Whether the proxy answering on the ports is the service's own, and who it is when not (#1570). */
class ProxyHolderTest {

    private static final String SELF = "sanne";

    /** A process table: pid to parent, pid to owner. */
    private record Table(Map<Long, Long> parents, Map<Long, String> owners) implements ProxyHolder.Processes {
        @Override public Optional<Long> parent(long pid) { return Optional.ofNullable(parents.get(pid)); }
        @Override public Optional<String> owner(long pid) { return Optional.ofNullable(owners.get(pid)); }
    }

    private static ProxyHealthCheck.ProxyInfo answering(long pid) {
        return new ProxyHealthCheck.ProxyInfo("0.3.12", "abc", "native", "fp", false, true, "", pid);
    }

    @Test
    void theServicesOwnProcessIsNotAHolder() {
        var table = new Table(Map.of(6710L, 1L), Map.of(6710L, SELF));
        assertNull(ProxyHolder.notTheService(answering(6710), 6710, table, SELF));
    }

    @Test
    void aChildOfTheServiceIsItsOwn() {
        // A unit or plist from an older build runs `isx proxy start`, whose child is isx-proxy.
        var table = new Table(Map.of(6711L, 6710L, 6710L, 1L), Map.of(6711L, SELF));
        assertNull(ProxyHolder.notTheService(answering(6711), 6710, table, SELF));
    }

    @Test
    void anotherUsersProxyIsNamedAsAnotherUsers() {
        var table = new Table(Map.of(80914L, 1L, 6710L, 1L), Map.of(80914L, "runner"));
        var holder = ProxyHolder.notTheService(answering(80914), 6710, table, SELF);
        assertNotNull(holder);
        assertTrue(holder.otherUser());
        assertEquals("isx-proxy 0.3.12, pid 80914, owned by user runner", holder.describe());
        assertTrue(holder.remedy().contains("runner"), holder.remedy());
    }

    @Test
    void whateverAnswersWhileTheServiceHasNoProcessIsNotIt() {
        // launchd between two retries of a job that cannot bind: no pid at all.
        var table = new Table(Map.of(), Map.of(80914L, "runner"));
        var holder = ProxyHolder.notTheService(answering(80914), 0, table, SELF);
        assertNotNull(holder);
        assertTrue(holder.otherUser());
    }

    @Test
    void anOrphanOfThisUsersIsAHolderButNotAnotherUsers() {
        var table = new Table(Map.of(4242L, 1L), Map.of(4242L, SELF));
        var holder = ProxyHolder.notTheService(answering(4242), 6710, table, SELF);
        assertNotNull(holder);
        assertFalse(holder.otherUser());
        assertTrue(holder.remedy().contains("kill 4242"), holder.remedy());
    }

    @Test
    void aRootOwnedProxyIsStoppedWithSudoNotAskedOfAService() {
        // Most likely this user's own `sudo isx proxy start`: root runs no proxy service.
        var table = new Table(Map.of(), Map.of(4242L, "root"));
        var holder = ProxyHolder.notTheService(answering(4242), 6710, table, SELF);
        assertTrue(holder.otherUser());
        assertTrue(holder.remedy().contains("sudo kill 4242"), holder.remedy());
        assertFalse(holder.remedy().contains("uninstall"), holder.remedy());
    }

    @Test
    void anUnknownOwnerIsNeverCalledAnotherUsers() {
        var holder = ProxyHolder.notTheService(answering(4242), 6710, new Table(Map.of(), Map.of()), SELF);
        assertNotNull(holder);
        assertFalse(holder.otherUser(), "only a known owner decides that the ports cannot be waited for");
        assertEquals("isx-proxy 0.3.12, pid 4242, owner unknown", holder.describe());
    }

    @Test
    void aProxyThatReportsNoPidIsNotJudged() {
        assertNull(ProxyHolder.notTheService(answering(-1), 6710, new Table(Map.of(), Map.of()), SELF));
        assertNull(ProxyHolder.notTheService(null, 6710, new Table(Map.of(), Map.of()), SELF));
    }

    @Test
    void ancestryEndsAtInit() {
        // A parent chain that reaches pid 1 without passing the service is not the service's.
        var table = new Table(Map.of(5L, 4L, 4L, 1L), Map.of(5L, SELF));
        assertNotNull(ProxyHolder.notTheService(answering(5), 1, table, SELF));
    }
}
