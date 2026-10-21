package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.util.CertChain;
import dev.incusspawn.util.MacKeychainTrust;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

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

    @Test
    void aChainHoldingADeniedCertificateIsRefusedUpstream() throws Exception {
        // A deny only removes anchors, and an intermediate or a leaf is never one (#1552)
        var chain = CertChain.create("upstream deny", "localhost");
        var otherHost = CertChain.create("upstream host", "elsewhere.example");
        var bundle = Files.writeString(temp.resolve("cert.pem"), pem(chain.root()) + pem(otherHost.root()));
        var server = chain.serve();
        var otherHostServer = otherHost.serve();
        var vertx = Vertx.vertx();
        try {
            var port = server.getAddress().getPort();
            var unrelatedDeny = new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(ca("unrelated"))), null);
            assertEquals(204, get(vertx, bundle, unrelatedDeny, "localhost", port));
            // The wrapper keeps the JDK trust manager's host-name check: a valid chain, for another host
            assertRefused(() -> get(vertx, bundle, unrelatedDeny, "localhost", otherHostServer.getAddress().getPort()));
            for (var denied : List.of(chain.intermediate(), chain.leaf())) {
                var keychain = new MacKeychainTrust.Trust(List.of(), Set.of(MacKeychainTrust.sha1(denied)), null);
                assertRefused(() -> get(vertx, bundle, keychain, "localhost", port));
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            server.stop(0);
            otherHostServer.stop(0);
        }
    }

    /** One GET through a client set up as the proxy's upstream client is: its status. */
    private static int get(Vertx vertx, Path bundle, MacKeychainTrust.Trust keychain, String host, int port) throws Exception {
        var options = new HttpClientOptions().setSsl(true).setVerifyHost(true)
                .setTrustOptions(Upstream.refusingDenied(vertx, Upstream.upstreamTrust(bundle, keychain, null), keychain));
        var client = vertx.createHttpClient(options);
        try {
            return client.request(HttpMethod.GET, port, host, "/").compose(HttpClientRequest::send)
                    .map(HttpClientResponse::statusCode).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    private static void assertRefused(Executable get) {
        var thrown = assertThrows(ExecutionException.class, get);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SSLHandshakeException) return;
        }
        fail("not refused by the TLS handshake", thrown);
    }

    private X509Certificate ca(String name) {
        System.setProperty("user.home", temp.resolve(name).toString());
        return CertificateAuthority.loadOrCreate().caCert();
    }

    private static String pem(X509Certificate cert) throws Exception {
        return DerEncoder.toPem("CERTIFICATE", cert.getEncoded());
    }
}
