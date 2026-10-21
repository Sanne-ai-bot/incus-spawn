package dev.incusspawn.util;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * A trust manager that refuses a chain holding a certificate the Keychain denied, after the JDK's
 * has validated it. Removing a denied certificate from the anchors only covers a root: macOS also
 * refuses a chain through a denied intermediate, or to a denied leaf, and so does this (#1552).
 * <p>
 * It checks the chain the peer presented, which is stricter than checking the path PKIX built: an
 * unused denied certificate the peer sends is refused too. The rest of that path is anchors, from
 * which every denied certificate is already removed, since the JDK fetches no intermediate itself
 * (AIA fetching, {@code com.sun.security.enableAIAcaIssuers}, is off and isx never turns it on).
 * <p>
 * Every overload goes to the delegate's own, so the {@code SSLEngine} and {@code Socket} ones keep
 * its endpoint identification: the host-name check.
 */
public final class DeniedChainTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager delegate;
    private final MacKeychainTrust.Trust keychain;

    private DeniedChainTrustManager(X509ExtendedTrustManager delegate, MacKeychainTrust.Trust keychain) {
        this.delegate = delegate;
        this.keychain = keychain;
    }

    /** {@code trustManagers} unchanged when the Keychain denies nothing, otherwise each one wrapped. */
    public static TrustManager[] wrap(TrustManager[] trustManagers, MacKeychainTrust.Trust keychain) {
        if (keychain.denied().isEmpty()) return trustManagers;
        var wrapped = new TrustManager[trustManagers.length];
        for (int i = 0; i < trustManagers.length; i++) {
            // The JDK's PKIX trust manager is always an extended one; anything else would lose the host-name check
            if (!(trustManagers[i] instanceof X509ExtendedTrustManager x509)) {
                throw new IllegalStateException("not an X509ExtendedTrustManager: " + trustManagers[i].getClass().getName());
            }
            wrapped[i] = new DeniedChainTrustManager(x509, keychain);
        }
        return wrapped;
    }

    private void refuseDenied(X509Certificate[] chain) throws CertificateException {
        for (var cert : chain) {
            if (keychain.denies(cert)) {
                throw new CertificateException("certificate denied in the Keychain's trust settings: "
                        + cert.getSubjectX500Principal().getName() + " (SHA-1 " + MacKeychainTrust.sha1(cert) + ")");
            }
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        refuseDenied(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
        refuseDenied(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
        refuseDenied(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
        refuseDenied(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
        refuseDenied(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
        refuseDenied(chain);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
