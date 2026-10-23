package dev.incusspawn.proxy;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/**
 * The proxy answering on the fixed ports when it is not the service's own (#1570): an orphan of
 * this user's, or, on a host two users share, another user's {@code isx-proxy}. Its {@code
 * /health} says it is healthy, so without this every check reads the ports as this user's proxy,
 * while the service retries its bind forever and instances talk to another CA, config and set of
 * credentials.
 * <p>
 * {@code user} is the holder's owner, or null when the host would not say. {@code otherUser} is
 * true only when that owner is known and is not this process's user: the one case neither this
 * user's service nor a restart of it can end.
 */
public record ProxyHolder(long pid, String version, String user, boolean otherUser) {

    /** How deep to walk a holder's ancestry looking for the service's process. */
    private static final int MAX_ANCESTRY = 8;

    /** How the host answers about a process; replaceable so tests never read real processes. */
    interface Processes {
        Optional<Long> parent(long pid);
        Optional<String> owner(long pid);

        Processes HOST = new Processes() {
            @Override
            public Optional<Long> parent(long pid) {
                return ProcessHandle.of(pid).flatMap(ProcessHandle::parent).map(ProcessHandle::pid);
            }

            @Override
            public Optional<String> owner(long pid) {
                return ProcessHandle.of(pid).flatMap(h -> h.info().user());
            }
        };
    }

    /**
     * The holder answering {@code info} when it is not the running service's process or one of
     * its descendants (a unit from an older build execs {@code isx proxy start}, whose child is
     * the proxy), else null. Only meaningful while the service is active: a proxy run by hand
     * with no service is nobody else's. A {@code servicePid} of 0 or less is a service with no
     * running process (launchd between two retries, systemd in auto-restart), so whatever
     * answers is not it.
     */
    public static ProxyHolder notTheService(ProxyHealthCheck.ProxyInfo info, long servicePid) {
        return notTheService(info, servicePid, Processes.HOST, System.getProperty("user.name"));
    }

    static ProxyHolder notTheService(ProxyHealthCheck.ProxyInfo info, long servicePid,
                                     Processes processes, String self) {
        // No pid (an older proxy) leaves nothing to compare: say nothing rather than guess.
        if (info == null || info.pid() <= 0) return null;
        if (servicePid > 0 && descendsFrom(info.pid(), servicePid, processes)) return null;
        return of(info, processes, self);
    }

    /** What is known about the proxy answering {@code info}, which must report its pid. */
    static ProxyHolder of(ProxyHealthCheck.ProxyInfo info, Processes processes, String self) {
        var user = processes.owner(info.pid()).filter(u -> !u.isBlank()).orElse(null);
        return new ProxyHolder(info.pid(), info.version(), user, user != null && !user.equals(self));
    }

    /** The holder of the ports a proxy of this user's could not bind, as {@link #of} describes it. */
    public static ProxyHolder answering(ProxyHealthCheck.ProxyInfo info) {
        if (info == null || info.pid() <= 0) return null;
        return of(info, Processes.HOST, System.getProperty("user.name"));
    }

    private static boolean descendsFrom(long pid, long ancestor, Processes processes) {
        var current = pid;
        for (int i = 0; i <= MAX_ANCESTRY; i++) {
            if (current == ancestor) return true;
            var parent = processes.parent(current);
            if (parent.isEmpty() || parent.get() <= 1) return false;
            current = parent.get();
        }
        return false;
    }

    /** "isx-proxy 0.3.12, pid 80914, owned by user runner". */
    public String describe() {
        var name = version == null || version.isEmpty() ? "isx-proxy" : "isx-proxy " + version;
        return name + ", pid " + pid + (user == null ? ", owner unknown" : ", owned by user " + user);
    }

    /**
     * Reports this holder to someone at a terminal: who holds the ports and how it ends. One
     * wording for {@code isx proxy install}, {@code isx proxy start} and {@code isx proxy status}.
     */
    public void report(Consumer<String> out) {
        out.accept(describe() + ", holds " + ProxyConfig.DEFAULT_MITM_PORT + "/"
                + ProxyConfig.DEFAULT_HEALTH_PORT + ": it is not this user's proxy service.");
        out.accept(remedy());
    }

    /** How the holder ends, for {@link #report} and {@code isx doctor}. */
    public String remedy() {
        if (otherUser && user.equals("root")) {
            // Most likely this user's own, started with sudo: root has no proxy service.
            return "It runs as root: stop it with 'sudo kill " + pid + "'; the service takes the ports once it is gone.";
        }
        if (otherUser) {
            // Killing it alone does not help: their service starts it again within seconds.
            return "Instances are talking to " + user + "'s proxy, with its CA, config and credentials."
                    + " Only one proxy can serve this host: ask " + user + " to stop their proxy service"
                    + " ('isx proxy uninstall'): killing only the process lets their service start it again.";
        }
        if (user == null) {
            return "Stop it as its owner (find it with 'ps -o user,pid,command -p " + pid
                    + "'); the service takes the ports once it is gone.";
        }
        return "Stop it with 'kill " + pid + "'; the service takes the ports once it is gone.";
    }
}
