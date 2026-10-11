package dev.incusspawn.command;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** #713: runtimes that carry their own root bundle trust the system store, and so the isx CA. */
class TrustBindingsTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    private static Container recordingContainer(IncusClient incus) {
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        return new Container(incus, "test");
    }

    private static List<String> writtenScripts(IncusClient incus) {
        var captor = ArgumentCaptor.forClass(String[].class);
        verify(incus, atLeast(0)).shellExec(eq("test"), captor.capture());
        return captor.getAllValues().stream().map(a -> String.join(" ", a)).toList();
    }

    @Test
    void envFileExportsTheTrustVariablesOnlyWhenUnset() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-x");

        GuestProvisioning.writeEnvFile(container, imageDef, Map.of("tpl-x", imageDef), List.of(), "tpl-x");

        var all = String.join("\n", writtenScripts(incus));
        assertTrue(all.contains("export SSL_CERT_FILE=\"${SSL_CERT_FILE:-/etc/pki/tls/certs/ca-bundle.crt}\""), all);
        assertTrue(all.contains("export REQUESTS_CA_BUNDLE=\"${REQUESTS_CA_BUNDLE:-"), all);
        assertTrue(all.contains("export NODE_EXTRA_CA_CERTS=\"${NODE_EXTRA_CA_CERTS:-"), all);
        assertTrue(all.contains("export DENO_TLS_CA_STORE=\"${DENO_TLS_CA_STORE:-system}\""), all);
    }

    @Test
    void aTemplatesOwnSetIfUnsetValueBeatsTheDefault() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-x");
        imageDef.setEnv(List.of(EnvEntry.setIfUnset("SSL_CERT_FILE", "/corp/ca.pem")));

        GuestProvisioning.writeEnvFile(container, imageDef, Map.of("tpl-x", imageDef), List.of(), "tpl-x");

        var all = String.join("\n", writtenScripts(incus));
        assertTrue(all.contains("${SSL_CERT_FILE:-/corp/ca.pem}"), all);
        assertFalse(all.contains("${SSL_CERT_FILE:-/etc/pki"), all);
    }

    @Test
    void theEnvironmentDropInSortsFirstSoAnyOtherFileOverridesIt() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);

        GuestProvisioning.writeTrustEnvironmentD(container);

        var all = String.join("\n", writtenScripts(incus));
        // First in filename order, so a user's own file wins; plain values, because the
        // ${NAME:-value} form broke rootless podman's registry pulls in CI.
        assertTrue(all.contains("cat > '/etc/environment.d/00-isx-trust.conf'"), all);
        assertTrue(all.contains("\nSSL_CERT_FILE=/etc/pki/tls/certs/ca-bundle.crt\n"), all);
        assertFalse(all.contains("${"), all);
    }

    @Test
    void javaIsNeverBoundThroughAVariable() {
        // JAVA_TOOL_OPTIONS and JDK_JAVA_OPTIONS print "Picked up ..." and break OpenJDK's
        // configure (#464); a Java store is bound by symlink only.
        assertFalse(GuestProvisioning.TRUST_ENV.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(GuestProvisioning.TRUST_ENV.containsKey("JDK_JAVA_OPTIONS"));
    }

    @Test
    void buildInstallsTheHelperTheDropInAndRunsTheHelperOnTheImageJdks() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);

        GuestProvisioning.bindTrustStores(container);

        var all = String.join("\n", writtenScripts(incus));
        assertTrue(all.contains("cat > '/usr/local/bin/isx-trust'"), all);
        assertTrue(all.contains("cat > '/etc/environment.d/00-isx-trust.conf'"), all);
        assertTrue(all.contains("SSL_CERT_FILE=/etc/pki/tls/certs/ca-bundle.crt"), all);
        assertTrue(all.contains("/usr/local/bin/isx-trust /usr/lib/jvm /opt"), all);
    }

    // --- the helper itself, run for real against fake JDK trees ---

    private static Path helper(Path dir) throws Exception {
        var script = dir.resolve("isx-trust");
        try (var is = TrustBindingsTest.class.getClassLoader().getResourceAsStream("isx-trust")) {
            assertNotNull(is, "isx-trust resource");
            Files.write(script, is.readAllBytes());
        }
        script.toFile().setExecutable(true);
        return script;
    }

    private static String run(Path script, Path store, Path... dirs) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add(script.toString());
        for (var d : dirs) cmd.add(d.toString());
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("ISX_TRUST_JAVA_STORE", store.toString());
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    @Test
    void helperLinksABundledCacertsAndLeavesTheRestAlone(@TempDir Path tmp) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var bundled = Files.createDirectories(tmp.resolve("opt/graalvm/lib/security"));
        var cacerts = Files.writeString(bundled.resolve("cacerts"), "vendor");
        var jsse = Files.writeString(bundled.resolve("jssecacerts"), "keep");
        var unrelated = Files.createDirectories(tmp.resolve("opt/other"));
        var other = Files.writeString(unrelated.resolve("cacerts"), "keep");

        var out = run(helper(tmp), store, tmp.resolve("opt"));

        assertTrue(Files.isSymbolicLink(cacerts), out);
        assertEquals("system", Files.readString(cacerts));
        assertEquals("keep", Files.readString(jsse));
        assertFalse(Files.isSymbolicLink(other));
        assertTrue(out.contains("now trusts the system store"), out);
    }

    @Test
    void helperIsSilentOnASecondRun(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var bundled = Files.createDirectories(tmp.resolve("opt/jdk/lib/security"));
        Files.writeString(bundled.resolve("cacerts"), "vendor");
        var script = helper(tmp);

        assertFalse(run(script, store, tmp.resolve("opt")).isEmpty());
        assertEquals("", run(script, store, tmp.resolve("opt")));
    }

    @Test
    void helperFollowsADirectoryThatIsASymlink(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var real = Files.createDirectories(tmp.resolve("graalvm-25/lib/security"));
        var cacerts = Files.writeString(real.resolve("cacerts"), "vendor");
        var link = Files.createSymbolicLink(tmp.resolve("graalvm"), tmp.resolve("graalvm-25"));

        run(helper(tmp), store, link);

        assertTrue(Files.isSymbolicLink(cacerts));
        assertEquals("system", Files.readString(cacerts));
    }

    @Test
    void helperIgnoresAMissingDirectory(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        assertEquals("", run(helper(tmp), store, tmp.resolve("nowhere")));
    }
}
