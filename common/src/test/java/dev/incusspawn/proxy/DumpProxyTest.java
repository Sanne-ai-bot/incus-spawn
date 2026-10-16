package dev.incusspawn.proxy;

import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.cert.Certificate;

import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(TempHome.class)
class DumpProxyTest {

    private CertificateAuthority ca;
    private SSLServerSocket server;
    private Thread acceptor;

    @BeforeEach
    void createCa() {
        ca = CertificateAuthority.loadOrCreate();
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.close();
        if (acceptor != null) acceptor.join(5_000);
    }

    @Test
    void acceptsTheUpstreamsOwnName() throws Exception {
        serve("localhost");
        try (var ignored = DumpProxy.openUpstream(clientTls().getSocketFactory(), "localhost", server.getLocalPort())) {
            // handshake completed
        }
    }

    @Test
    void rejectsATrustedCertificateForAnotherName() throws Exception {
        // A raw SSLSocket checks only the chain: any trusted CA's certificate for any host would pass
        serve("other.example");
        assertThrows(SSLHandshakeException.class,
                () -> DumpProxy.openUpstream(clientTls().getSocketFactory(), "localhost", server.getLocalPort()).close());
    }

    /** A TLS server on loopback presenting a certificate for {@code name}, signed by the test CA. */
    private void serve(String name) throws Exception {
        var leaf = ca.generateDomainCert(name);
        var keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("leaf", leaf.key(), new char[0], new Certificate[] {leaf.cert(), ca.caCert()});
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        var tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName("localhost"));
        acceptor = Thread.ofVirtual().start(() -> {
            try (var socket = (SSLSocket) server.accept()) {
                socket.startHandshake();
            } catch (IOException ignored) {
                // the client refused the certificate, or the test is over
            }
        });
    }

    /** Trusts the test CA alone, as the system store trusts an inspecting proxy's. */
    private SSLContext clientTls() throws Exception {
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", ca.caCert());
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, tmf.getTrustManagers(), null);
        return tls;
    }
}
