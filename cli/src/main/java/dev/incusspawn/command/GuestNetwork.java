package dev.incusspawn.command;

import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;

/**
 * Waits for a started guest's network before a step that needs it: an IPv4 address, then a
 * Fedora mirror that answers, which takes a name that resolves. Shared by {@code isx build} and
 * the updates ({@code isx update-all}, {@code isx project update}), whose templates boot on DHCP:
 * exec answers before the lease is in place, so a dnf run straight after cannot resolve its
 * mirrors (#1734).
 *
 * <p>Each wait runs in the guest, so a network already up costs one exec. Each returns why it
 * gave up, with what helps diagnose it, or null once the network is up.
 */
final class GuestNetwork {

    /** Reached the same way dnf reaches its mirrors; it answers with a redirect. */
    private static final String MIRROR = "https://mirrors.fedoraproject.org";

    /** Exit codes of the scripts: no address, no mirror. */
    private static final int NO_ADDRESS = 3;
    private static final int NO_MIRROR = 4;

    private static final String MIRROR_LOOP = "for i in $(seq 1 10); do "
            + "curl -4 -s -o /dev/null --max-time 30 -w '%{http_code}' " + MIRROR + " | grep -q 302 && exit 0; "
            + "sleep 2; done; exit " + NO_MIRROR;

    private GuestNetwork() {
    }

    /**
     * Up to 15 seconds for an address. A container's NIC is eth0, and its networkd is started
     * first, in case the build has just changed its configuration. A VM's NIC keeps its kernel's
     * name (#997), and its guest may manage its network otherwise, so it gets the default route
     * a lease brings, and is left alone.
     */
    private static String addressLoop(MachineType machineType) {
        var vm = machineType == MachineType.VM;
        var check = vm ? "ip -4 route show default | grep -q ." : "ip -4 -o addr show eth0 | grep -q 'inet '";
        return (vm ? "" : "systemctl start systemd-networkd 2>/dev/null; ")
                + "up=; for i in $(seq 1 30); do " + check + " && { up=1; break; }; sleep 0.5; done; "
                + "[ -n \"$up\" ] || exit " + NO_ADDRESS + "; ";
    }

    /** A build container's address; a VM's build waits for the mirror alone. */
    static String waitForAddress(IncusClient incus, String name) {
        return run(incus, name, addressLoop(MachineType.CONTAINER) + "exit 0");
    }

    static String waitForMirror(IncusClient incus, String name) {
        return run(incus, name, MIRROR_LOOP);
    }

    /** Both waits in one exec, for a guest whose DNS configuration needs no change in between. */
    static String waitUntilOnline(IncusClient incus, String name, MachineType machineType) {
        return run(incus, name, addressLoop(machineType) + MIRROR_LOOP);
    }

    /** Why the wait {@code script} makes failed, or null when it did not. */
    private static String run(IncusClient incus, String name, String script) {
        var result = incus.shellExec(name, "sh", "-c", script);
        if (result.success()) return null;
        if (result.exitCode() == NO_ADDRESS) {
            var diag = incus.shellExec(name, "sh", "-c",
                    "echo '--- systemd-networkd status ---'; "
                    + "systemctl status systemd-networkd 2>&1 || true; "
                    + "echo '--- networkctl ---'; "
                    + "networkctl status 2>&1 || true; "
                    + "echo '--- ip address ---'; "
                    + "ip -4 addr 2>&1 || true; "
                    + "echo '--- ip link ---'; "
                    + "ip link 2>&1 || true; "
                    + "echo '--- journalctl networkd ---'; "
                    + "journalctl -u systemd-networkd --no-pager -n 20 2>&1 || true");
            return name + " did not acquire an IPv4 address within 15 seconds.\n"
                    + "Diagnostics:\n" + diag.stdout() + diag.stderr();
        }
        if (result.exitCode() != NO_MIRROR) {
            return "Could not check the network of " + name + " (exit code " + result.exitCode() + ")"
                    + (result.stderr().isBlank() ? "." : ":\n" + result.stderr().strip());
        }
        var message = new StringBuilder(name + " cannot reach " + MIRROR
                + ": DNS resolution is not working. Check your network setup.");
        var subnet = BridgeSubnetCheck.detectConflictDiagnostic(incus);
        if (subnet != null) message.append("\n\n").append(subnet);
        var firewall = FirewallDetector.detectDiagnostic();
        if (firewall != null) message.append("\n\n").append(firewall);
        return message.toString();
    }
}
