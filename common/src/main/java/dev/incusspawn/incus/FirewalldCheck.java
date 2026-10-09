package dev.incusspawn.incus;

import dev.incusspawn.util.HostPath;

import java.io.IOException;
import java.util.List;

public final class FirewalldCheck {

    private FirewalldCheck() {}

    public static boolean isInstalled() {
        return HostPath.isOnPath("firewall-cmd");
    }

    public static boolean isActive() {
        try {
            return probeActive();
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Like {@link #isActive()}, but a probe that was cut short throws rather than reading as
     * stopped. A host without {@code systemctl} (macOS, Linux without systemd) is an answer: not active.
     */
    static boolean probeActive() throws IOException, InterruptedException {
        // systemctl is-active does not require root, unlike firewall-cmd --state
        // which needs polkit authorization and fails with exit 253 as a normal user
        return probeActive(List.of("systemctl", "is-active", "firewalld"));
    }

    static boolean probeActive(List<String> command) throws IOException, InterruptedException {
        if (!HostPath.isOnPath(command.getFirst())) return false;
        var pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes()).strip();
        return process.waitFor() == 0 && "active".equals(output);
    }

    static final FirewallDetector.Stopped STOPPED = new FirewallDetector.Stopped(
            "firewalld is not running:",
            "Possible cause: firewalld is installed but not running.\n"
            + "Firewall rules (masquerading, FORWARD, PREROUTING redirect) are not\n"
            + "loaded into the kernel, so containers cannot reach the internet.\n\n"
            + "Fix:\n"
            + "  sudo systemctl enable --now firewalld\n"
            + "  isx init");

    /**
     * Whether a line matches the PREROUTING redirect rule pattern (incusbr0, port 443 → mitmPort).
     * Works for both {@code firewall-cmd --direct --get-all-rules} output and persistent
     * {@code /etc/firewalld/direct.xml} element text.
     */
    public static boolean isRedirectRule(String line, int mitmPort) {
        return line.contains("PREROUTING")
                && line.contains("incusbr0")
                && containsToken(line, "--dport", "443")
                && line.contains("REDIRECT")
                && containsToken(line, "--to-port", String.valueOf(mitmPort));
    }

    private static boolean containsToken(String line, String flag, String value) {
        var token = flag + " " + value;
        int idx = line.indexOf(token);
        if (idx < 0) return false;
        int end = idx + token.length();
        return end >= line.length() || !Character.isDigit(line.charAt(end));
    }

    public static boolean isPreRoutingRulePresent(String firewalldOutput, int mitmPort, String gatewayIp) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("nat") && isRedirectRule(line, mitmPort)
                    && line.contains("-d " + gatewayIp)) {
                return true;
            }
        }
        return false;
    }

    /** Extract the gateway IP from an existing PREROUTING redirect rule, or null if none found. */
    public static String extractRedirectGatewayIp(String firewalldOutput, int mitmPort) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("nat") && isRedirectRule(line, mitmPort)
                    && line.contains("-d ")) {
                int idx = line.indexOf("-d ");
                if (idx >= 0) {
                    var rest = line.substring(idx + 3).strip();
                    int end = rest.indexOf(' ');
                    return end > 0 ? rest.substring(0, end) : rest;
                }
            }
        }
        return null;
    }

    public static boolean isForwardRulePresent(String firewalldOutput, String interfaceFlag, String interfaceName) {
        for (var line : firewalldOutput.split("\n")) {
            if (line.contains("FORWARD")
                    && line.contains(interfaceFlag + " " + interfaceName)
                    && line.contains("ACCEPT")) {
                return true;
            }
        }
        return false;
    }

}
