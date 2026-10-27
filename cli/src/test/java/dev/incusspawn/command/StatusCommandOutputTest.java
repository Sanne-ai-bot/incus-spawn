package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck.ProxyInfo;
import dev.incusspawn.proxy.ProxyHealthCheck.ProxyStatus;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx proxy status} and {@code isx vm status} off a terminal (#1037): the report is the
 * result and goes to stdout whatever it says, and the exit code is what a script checks.
 */
@ExtendWith(IsolatedHome.class)
class StatusCommandOutputTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
    private final PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);

    /** A running proxy's report is the command's result: its state, then one aligned field a line. */
    @Test
    void aRunningProxyIsReportedAsResults() {
        var build = BuildInfo.instance();
        var info = new ProxyInfo(build.version(), build.gitSha(), "native",
                null, false, true, null);
        var recorded = new RecordingOutput();

        assertEquals(0, ProxyCommand.Status.reportRunning(ProxyStatus.RUNNING, info, "10.0.0.1", null, true, false,
                recorded.results()));
        assertEquals(List.of(
                "Proxy is running.",
                "  Version:         " + build.version() + " (" + build.gitSha() + ")",
                "  Runtime:         native",
                "  DNS overrides:   active",
                "  Health endpoint: http://10.0.0.1:" + ProxyConfig.DEFAULT_HEALTH_PORT + "/health",
                "  MITM port:       " + ProxyConfig.DEFAULT_MITM_PORT,
                "  Managed by:      systemd (incus-spawn-proxy.service)"), recorded.resultLines());
        assertEquals(recorded.resultLines(), recorded.lines(), "nothing but the report");
    }

    @Test
    void proxyDownIsReportedOnStdoutWithItsExitCode() {
        assertEquals(1, ProxyCommand.Status.reportDown(ProxyStatus.NOT_RUNNING, false, false, outStream));
        assertEquals(2, ProxyCommand.Status.reportDown(ProxyStatus.STALE_DNS, true, false, outStream));
        assertEquals(3, ProxyCommand.Status.reportDown(ProxyStatus.STALE_GATEWAY, true, true, outStream));
        assertEquals("""
                Proxy is not running.
                Start it with: isx proxy start
                Or install as a service: isx proxy install
                Proxy is not running, but DNS overrides are still active.
                Start the proxy to restore connectivity: isx proxy start
                Proxy is running, but on an old address of incusbr0 that instances cannot reach.
                Restart it to bind the current address: isx proxy restart
                """, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void vmStatusFailsWhenIncusIsUnreachable() {
        var unreachable = new IncusClient() {
            @Override public String checkConnectivity() { return "no socket"; }
        };
        assertEquals(1, VmCommand.Status.report("VM not running", unreachable, outStream, errStream));
        assertEquals("VM not running\n", out.toString(StandardCharsets.UTF_8));
        assertEquals("\nIncus not reachable: no socket\n", err.toString(StandardCharsets.UTF_8));
    }
}
