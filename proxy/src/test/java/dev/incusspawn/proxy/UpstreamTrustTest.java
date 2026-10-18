package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.util.MacKeychainTrust;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What the proxy's upstream connections trust: the system bundle alone, and on macOS the Keychain's admin trust beside it (#1467). */
class UpstreamTrustTest {

    @TempDir
    Path temp;

    private String savedHome;

    @BeforeEach
    void saveHome() {
        savedHome = System.getProperty("user.home");
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", savedHome);
    }

    @Test
    void withNothingFromTheKeychainTheBundleIsReadByPath() throws Exception {
        var bundle = Files.writeString(temp.resolve("cert.pem"), pem(ca("bundle")));
        var trust = Upstream.upstreamTrust(bundle, MacKeychainTrust.Trust.NONE, null);
        assertEquals(List.of(bundle.toString()), trust.getCertPaths());
        assertEquals(List.of(), trust.getCertValues());
        assertNull(Upstream.upstreamTrust(null, MacKeychainTrust.Trust.NONE, null));
    }

    @Test
    void onMacOsTheKeychainsAdminTrustJoinsTheBundleLessWhatWasDenied() throws Exception {
        var inBundle = ca("bundle");
        var deniedInBundle = ca("denied");
        var inKeychain = ca("keychain");
        var bundle = Files.writeString(temp.resolve("cert.pem"), pem(inBundle) + pem(deniedInBundle));
        var keychain = new MacKeychainTrust.Trust(List.of(inKeychain), Set.of(MacKeychainTrust.sha1(deniedInBundle)), null);

        var trust = Upstream.upstreamTrust(bundle, keychain, null);

        assertEquals(List.of(), trust.getCertPaths());
        assertEquals(List.of(pem(inBundle), pem(inKeychain)), trust.getCertValues().stream().map(Object::toString).toList());
    }

    @Test
    void whenTheKeychainLeavesNothingTrustedTheProxyRefusesRatherThanFallingBackToTheJdkStore() throws Exception {
        // No trust options would mean Vert.x's default: the JDK store, the denied roots included
        var bundled = ca("bundle");
        var bundle = Files.writeString(temp.resolve("cert.pem"), pem(bundled));
        var defaultRootsDenied = new MacKeychainTrust.Trust(List.of(), Set.of(), true, null);
        assertThrows(IllegalStateException.class, () -> Upstream.upstreamTrust(bundle, defaultRootsDenied, null));
        assertThrows(IllegalStateException.class, () -> Upstream.upstreamTrust(null, defaultRootsDenied, null));
        var everythingDenied = new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(bundled)), null);
        assertThrows(IllegalStateException.class, () -> Upstream.upstreamTrust(bundle, everythingDenied, null));
    }

    @Test
    void aBenchmarkStubsCertificateIsStillAdded() throws Exception {
        var stub = Files.writeString(temp.resolve("stub.pem"), pem(ca("stub")));
        var trust = Upstream.upstreamTrust(null, new MacKeychainTrust.Trust(List.of(), Set.of(), null), stub.toString());
        assertEquals(List.of(stub.toString()), trust.getCertPaths());
    }

    private X509Certificate ca(String name) {
        System.setProperty("user.home", temp.resolve(name).toString());
        return CertificateAuthority.loadOrCreate().caCert();
    }

    private static String pem(X509Certificate cert) throws Exception {
        return DerEncoder.toPem("CERTIFICATE", cert.getEncoded());
    }
}
