package dev.incusspawn.util;

import com.sun.net.httpserver.HttpsServer;
import dev.incusspawn.DerEncoder;
import dev.incusspawn.proxy.CertificateAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A local HTTPS server whose CA is only in a temporary "system" bundle: what TLS inspection looks
 * like to isx, with the inspecting proxy's CA added to the system store and unknown to the JDK's.
 */
class SystemTrustTest {

    @TempDir
    Path temp;

    private String savedHome;
    private CertificateAuthority inspectingCa;
    private CertificateAuthority otherCa;
    private HttpsServer server;

    @BeforeEach
    void startServer() throws Exception {
        savedHome = System.getProperty("user.home");
        inspectingCa = caUnder(temp.resolve("inspecting"));
        otherCa = caUnder(temp.resolve("other"));

        var leaf = inspectingCa.generateDomainCert("localhost");
        server = CertChain.serve(leaf.key(), leaf.cert(), inspectingCa.caCert());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        System.setProperty("user.home", savedHome);
    }

    private static CertificateAuthority caUnder(Path home) {
        System.setProperty("user.home", home.toString());
        return CertificateAuthority.loadOrCreate();
    }

    /** Not {@code caCertPem()}: that reads whichever CA the current {@code user.home} holds. */
    private static String pem(CertificateAuthority ca) throws Exception {
        return DerEncoder.toPem("CERTIFICATE", ca.caCert().getEncoded());
    }

    @Test
    void trustsACaOnlyTheSystemBundleHolds() throws Exception {
        // Shaped like Fedora's tls-ca-bundle.pem: a comment before each certificate, ours not first
        var bundle = Files.writeString(temp.resolve("tls-ca-bundle.pem"),
                "# Other CA\n" + pem(otherCa) + "\n# Inspecting CA\n" + pem(inspectingCa));
        assertEquals(204, get(SystemTrust.sslContext(bundle, MacKeychainTrust.Trust.NONE)));
    }

    @Test
    void withoutABundleTheJdkStoreApplies() {
        assertThrows(SSLHandshakeException.class, () -> get(SystemTrust.sslContext(null, MacKeychainTrust.Trust.NONE)));
    }

    @Test
    void addsTheBundleToTheJdkStoreRatherThanReplacingIt() throws Exception {
        // A CA a user imported into a JVM's cacerts must keep working
        var bundle = Files.writeString(temp.resolve("cert.pem"), pem(otherCa));
        var jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jdk.init((KeyStore) null);
        var expected = new HashSet<Certificate>(List.of(((X509TrustManager) jdk.getTrustManagers()[0]).getAcceptedIssuers()));
        expected.add(otherCa.caCert());

        var anchors = SystemTrust.anchors(bundle, MacKeychainTrust.Trust.NONE);
        var actual = new HashSet<Certificate>();
        for (var alias : Collections.list(anchors.aliases())) actual.add(anchors.getCertificate(alias));
        assertEquals(expected, actual);
        assertThrows(SSLHandshakeException.class, () -> get(SystemTrust.sslContext(bundle, MacKeychainTrust.Trust.NONE)));
    }

    @Test
    void anUnreadableBundleLeavesTheJdkStore() throws Exception {
        var garbage = Files.writeString(temp.resolve("garbage.pem"), "not a certificate\n");
        assertThrows(SSLHandshakeException.class, () -> get(SystemTrust.sslContext(garbage, MacKeychainTrust.Trust.NONE)));
        assertThrows(SSLHandshakeException.class, () -> get(SystemTrust.sslContext(temp.resolve("missing.pem"), MacKeychainTrust.Trust.NONE)));
    }

    @Test
    void trustsACaTheKeychainTrusts() throws Exception {
        // macOS: the inspection CA is in the System keychain, not in /etc/ssl/cert.pem (#1467)
        var bundle = Files.writeString(temp.resolve("cert.pem"), pem(otherCa));
        var keychain = new MacKeychainTrust.Trust(List.of(inspectingCa.caCert()), Set.of(), null);
        assertEquals(204, get(SystemTrust.sslContext(bundle, keychain)));
        assertEquals(List.of(otherCa.caCert(), inspectingCa.caCert()), SystemTrust.upstreamAnchors(bundle, keychain));
    }

    @Test
    void whatAnAdministratorDeniedIsTrustedFromNoSource() throws Exception {
        var bundle = Files.writeString(temp.resolve("cert.pem"), pem(inspectingCa));
        var deniedInspecting = new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(inspectingCa.caCert())), null);
        assertThrows(SSLHandshakeException.class, () -> get(SystemTrust.sslContext(bundle, deniedInspecting)));
        // Nothing left for the proxy: refused, not an empty set Vert.x would replace with the JDK store
        assertThrows(java.security.GeneralSecurityException.class, () -> SystemTrust.upstreamAnchors(bundle, deniedInspecting));

        // The JDK store's roots too
        var jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jdk.init((KeyStore) null);
        var aJdkRoot = ((X509TrustManager) jdk.getTrustManagers()[0]).getAcceptedIssuers()[0];
        var deniedRoot = new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(aJdkRoot)), null);
        var anchors = SystemTrust.anchors(null, deniedRoot);
        for (var alias : Collections.list(anchors.aliases())) {
            assertNotEquals(aJdkRoot, anchors.getCertificate(alias));
        }
        assertEquals(((X509TrustManager) jdk.getTrustManagers()[0]).getAcceptedIssuers().length - 1, anchors.size());
    }

    @Test
    void withoutABundleTheProxyKeepsTheJdkStoreBesideTheKeychain() throws Exception {
        // The proxy trusts the JDK store when there is no bundle: the Keychain must add to it, not replace it
        var jdkRoots = List.of(((X509TrustManager) jdkTrustManager()).getAcceptedIssuers());
        var keychain = new MacKeychainTrust.Trust(List.of(inspectingCa.caCert()), Set.of(MacKeychainTrust.sha1(jdkRoots.getFirst())), null);
        var anchors = SystemTrust.upstreamAnchors(null, keychain);
        assertEquals(jdkRoots.size(), anchors.size());
        assertTrue(anchors.contains(inspectingCa.caCert()));
        assertFalse(anchors.contains(jdkRoots.getFirst()), "an administrator denied it");
    }

    @Test
    void aDenyOfTheDefaultRootsLeavesOnlyWhatAnAdministratorTrusts() throws Exception {
        var bundle = Files.writeString(temp.resolve("cert.pem"), pem(otherCa));
        var keychain = new MacKeychainTrust.Trust(List.of(inspectingCa.caCert()), Set.of(), true, null);
        assertEquals(List.of(inspectingCa.caCert()), SystemTrust.upstreamAnchors(bundle, keychain));
        assertEquals(1, SystemTrust.anchors(bundle, keychain).size());
    }

    @Test
    void aChainThroughADeniedIntermediateIsRefused() throws Exception {
        // macOS refuses a chain holding any denied certificate, and an intermediate is never an anchor (#1552)
        var chain = CertChain.create("deny intermediate", "localhost");
        var bundle = Files.writeString(temp.resolve("cert.pem"), DerEncoder.toPem("CERTIFICATE", chain.root().getEncoded()));
        var publicServer = chain.serve();
        try {
            assertEquals(204, get(SystemTrust.sslContext(bundle, MacKeychainTrust.Trust.NONE), "localhost", publicServer));
            var deniedIntermediate = new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(chain.intermediate())), null);
            assertThrows(SSLHandshakeException.class, () -> get(SystemTrust.sslContext(bundle, deniedIntermediate), "localhost", publicServer));
            var deniedLeaf = new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(chain.leaf())), null);
            assertThrows(SSLHandshakeException.class, () -> get(SystemTrust.sslContext(bundle, deniedLeaf), "localhost", publicServer));
        } finally {
            publicServer.stop(0);
        }
    }

    @Test
    void withDeniesTheHostNameIsStillChecked() throws Exception {
        // The deny check wraps the JDK's trust manager: it must keep its endpoint identification
        // A valid chain to a trusted root, for another host (aChainThroughADeniedIntermediateIsRefused shows such a chain validates)
        var chain = CertChain.create("host name", "elsewhere.example");
        var bundle = Files.writeString(temp.resolve("cert.pem"), DerEncoder.toPem("CERTIFICATE", chain.root().getEncoded()));
        var publicServer = chain.serve();
        try {
            var unrelatedDeny = new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(otherCa.caCert())), null);
            assertThrows(SSLHandshakeException.class, () -> get(SystemTrust.sslContext(bundle, unrelatedDeny), "localhost", publicServer));
        } finally {
            publicServer.stop(0);
        }
    }

    private static javax.net.ssl.TrustManager jdkTrustManager() throws Exception {
        var jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jdk.init((KeyStore) null);
        return jdk.getTrustManagers()[0];
    }

    private int get(SSLContext tls) throws Exception {
        return get(tls, "localhost", server);
    }

    private static int get(SSLContext tls, String host, HttpsServer server) throws Exception {
        try (var client = HttpClient.newBuilder().sslContext(tls).build()) {
            var request = HttpRequest.newBuilder(URI.create("https://" + host + ":" + server.getAddress().getPort() + "/")).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
