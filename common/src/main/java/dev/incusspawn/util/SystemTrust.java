package dev.incusspawn.util;

import dev.incusspawn.ClientLog;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.List;

/**
 * The host's CA store, which isx trusts for its own HTTPS: the proxy's upstream connections and
 * every {@code HttpClient} the CLI builds. A native binary otherwise trusts only the JDK store baked
 * in when it was built, so behind TLS inspection, whose CA an administrator adds to the system
 * store, it fails where curl and the proxy succeed (#1437).
 * <p>
 * The bundle's certificates are added to the JDK store, never in place of it: a CA a user
 * imported into a JVM's {@code cacerts} or named with {@code javax.net.ssl.trustStore} (the usual
 * workaround before this) keeps working, and a bundle that cannot be read leaves the JDK store as
 * before. Nothing is cached: this class is initialized at image build time, and the store may
 * change while a long-running isx is up.
 */
public final class SystemTrust {

    // Strings, not Paths: this list is in the image heap
    private static final List<String> CA_BUNDLES = List.of(
            "/etc/ssl/cert.pem",                                    // Fedora (symlink), macOS, Alpine
            "/etc/ssl/certs/ca-certificates.crt",                   // Debian, Ubuntu
            "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem");   // RHEL, CentOS

    private SystemTrust() {}

    /** The first system CA bundle (PEM) that exists, or null. */
    public static Path findBundle() {
        return CA_BUNDLES.stream().map(Path::of).filter(Files::exists).findFirst().orElse(null);
    }

    /** An {@code HttpClient} builder that trusts the system CA store. */
    public static HttpClient.Builder httpClient() {
        return HttpClient.newBuilder().sslContext(sslContext());
    }

    /**
     * A TLS context that trusts the system CA store, for what is not an {@code HttpClient}. A raw
     * socket from it checks the chain but not the host name: set endpoint identification to
     * {@code HTTPS} on it before the handshake, as {@code DumpProxy.openUpstream} does.
     */
    public static SSLContext sslContext() {
        return sslContext(findBundle());
    }

    /** A TLS context trusting the JDK store plus the certificates in {@code bundle}, which may be null. */
    static SSLContext sslContext(Path bundle) {
        try {
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(anchors(bundle));
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, tmf.getTrustManagers(), null);
            return tls;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No TLS context: " + e.getMessage(), e);
        }
    }

    /** The JDK store's anchors, then the bundle's; the bundle's are left out if it cannot be read. */
    static KeyStore anchors(Path bundle) throws GeneralSecurityException {
        var anchors = KeyStore.getInstance("PKCS12");
        try {
            anchors.load(null, null);
        } catch (IOException e) {
            throw new KeyStoreException(e);
        }
        var jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jdk.init((KeyStore) null);
        int i = 0;
        for (var tm : jdk.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                for (var cert : x509.getAcceptedIssuers()) anchors.setCertificateEntry("jdk-" + i++, cert);
            }
        }
        if (bundle == null) return anchors;
        try (var in = Files.newInputStream(bundle)) {
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                anchors.setCertificateEntry("system-" + i++, cert);
            }
        } catch (IOException | CertificateException e) {
            ClientLog.warn("System CA bundle " + bundle + " not usable, trusting the JDK's alone: " + e.getMessage());
        }
        return anchors;
    }
}
