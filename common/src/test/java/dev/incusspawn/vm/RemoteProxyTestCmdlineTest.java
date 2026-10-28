package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code appliance/test-proxy-remote.sh} boots the appliance with the gateway, MITM port and proxy
 * mode isx boots it with on macOS, copied by hand. If {@link VmManager#kernelCmdline()} moves
 * one of them, the script would go on passing on a shape no Mac boots (#1585).
 */
class RemoteProxyTestCmdlineTest {

    private static final Path SCRIPT = Path.of("../appliance/test-proxy-remote.sh");

    @Test
    void scriptBootsWithTheCmdlineIsxUsesOnMacOs() throws IOException {
        assumeTrue(unset("ISX_GATEWAY") && unset("ISX_MITM_PORT"), "the cmdline follows ISX_GATEWAY and ISX_MITM_PORT");
        var script = Files.readString(SCRIPT);
        var cmdline = VmManager.kernelCmdline();

        assertTrue(cmdline.contains(" isx.gateway=" + assignment(script, "GATEWAY") + " "), cmdline);
        assertTrue(cmdline.contains(" isx.mitm_port=" + assignment(script, "MITM_PORT") + " "), cmdline);
        assertTrue(cmdline.contains(" isx.proxy=remote"), cmdline);
        assertTrue(script.contains("isx.gateway=$GATEWAY isx.mitm_port=$MITM_PORT isx.proxy=remote"),
                "the script boots with its GATEWAY, MITM_PORT and remote proxy mode");
    }

    private static boolean unset(String name) {
        var value = System.getenv(name);
        return value == null || value.isBlank();
    }

    private static String assignment(String script, String name) {
        var m = Pattern.compile("(?m)^" + name + "=(\\S+)$").matcher(script);
        if (!m.find()) fail(SCRIPT + " no longer sets " + name);
        return m.group(1);
    }
}
