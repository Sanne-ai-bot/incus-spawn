package dev.incusspawn.util;

import dev.incusspawn.ClientLog;
import dev.incusspawn.Platform;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
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
 * <p>
 * On macOS an inspection CA is in the Keychain, not in {@code /etc/ssl/cert.pem}, so the
 * certificates an administrator trusts for SSL there are added too, and those an administrator or
 * the user denied are taken out of every source, the JDK store included ({@link MacKeychainTrust},
 * #1467), and a chain holding one is refused ({@link DeniedChainTrustManager}, #1552).
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
        return sslContext(findBundle(), keychain());
    }

    /** A TLS context trusting {@link #anchors}: {@code bundle} may be null. */
    static SSLContext sslContext(Path bundle, MacKeychainTrust.Trust keychain) {
        try {
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(anchors(bundle, keychain));
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, DeniedChainTrustManager.wrap(tmf.getTrustManagers(), keychain), null);
            return tls;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No TLS context: " + e.getMessage(), e);
        }
    }

    /** On macOS the Keychain's admin trust settings, read now; elsewhere none. */
    public static MacKeychainTrust.Trust keychain() {
        if (!Platform.isMacOS()) return MacKeychainTrust.Trust.NONE;
        var trust = MacKeychainTrust.read();
        if (trust.problem() != null) {
            ClientLog.warn("Keychain not fully read, its CAs left out" + (trust.denied().isEmpty() && !trust.defaultRootsDenied()
                    ? "" : " (its denies still apply)") + ": " + trust.problem());
        }
        return trust;
    }

    /**
     * The proxy's upstream trust when the Keychain adds or denies something: the bundle, or the JDK
     * store when there is none or it cannot be read (the proxy's rule without a Keychain), then the
     * Keychain's admin-trusted certificates, less what the Keychain denied. Throws when nothing
     * is left, as the CLI's empty trust store does.
     */
    public static List<X509Certificate> upstreamAnchors(Path bundle, MacKeychainTrust.Trust keychain) throws GeneralSecurityException {
        var base = bundleCertificates(bundle);
        var anchors = withKeychain(base != null ? base : jdkAnchors(), keychain);
        // Empty would leave the proxy with Vert.x's default, the JDK store, denied roots included
        if (anchors.isEmpty()) {
            throw new GeneralSecurityException("the Keychain's trust settings deny every CA and trust none "
                    + "(see `security dump-trust-settings -d` for an administrator's, and without -d for your own)");
        }
        return anchors;
    }

    /** The JDK store's anchors, then the bundle's and the Keychain's, less what the Keychain denied. */
    static KeyStore anchors(Path bundle, MacKeychainTrust.Trust keychain) throws GeneralSecurityException {
        var anchors = KeyStore.getInstance("PKCS12");
        try {
            anchors.load(null, null);
        } catch (IOException e) {
            throw new KeyStoreException(e);
        }
        var certs = jdkAnchors();
        var bundled = bundleCertificates(bundle);
        if (bundled != null) certs.addAll(bundled);
        int i = 0;
        for (var cert : withKeychain(certs, keychain)) anchors.setCertificateEntry("anchor-" + i++, cert);
        return anchors;
    }

    /**
     * {@code base} with the Keychain applied: its admin-trusted certificates added, what it denied
     * removed, and {@code base} dropped whole when it denied the default roots.
     */
    private static List<X509Certificate> withKeychain(List<X509Certificate> base, MacKeychainTrust.Trust keychain) {
        var certs = new ArrayList<X509Certificate>();
        if (!keychain.defaultRootsDenied()) certs.addAll(base);
        certs.addAll(keychain.trusted());
        certs.removeIf(keychain::denies);
        return certs;
    }

    /** The JDK store's anchors: on a JVM the one {@code javax.net.ssl.trustStore} names, in a native binary the one baked in. */
    private static List<X509Certificate> jdkAnchors() throws GeneralSecurityException {
        var jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jdk.init((KeyStore) null);
        var certs = new ArrayList<X509Certificate>();
        for (var tm : jdk.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) certs.addAll(List.of(x509.getAcceptedIssuers()));
        }
        return certs;
    }

    /** The bundle's certificates, or null when there is none or it cannot be read. */
    private static List<X509Certificate> bundleCertificates(Path bundle) {
        if (bundle == null) return null;
        try (var in = Files.newInputStream(bundle)) {
            return certificates(in);
        } catch (IOException | CertificateException e) {
            ClientLog.warn("System CA bundle " + bundle + " not usable, leaving it out: " + e.getMessage());
            return null;
        }
    }

    /** The certificates in a PEM stream, text between them ignored; none when it is empty. */
    public static List<X509Certificate> certificates(InputStream pem) throws CertificateException {
        var certs = new ArrayList<X509Certificate>();
        for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(pem)) certs.add((X509Certificate) cert);
        return certs;
    }
}
