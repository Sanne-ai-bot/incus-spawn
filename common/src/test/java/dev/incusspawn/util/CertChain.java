package dev.incusspawn.util;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

import static dev.incusspawn.DerEncoder.concat;
import static dev.incusspawn.DerEncoder.derBitString;
import static dev.incusspawn.DerEncoder.derDistinguishedName;
import static dev.incusspawn.DerEncoder.derDnsName;
import static dev.incusspawn.DerEncoder.derExplicit;
import static dev.incusspawn.DerEncoder.derExtension;
import static dev.incusspawn.DerEncoder.derInteger;
import static dev.incusspawn.DerEncoder.derSequence;
import static dev.incusspawn.DerEncoder.derUtcTime;
import static dev.incusspawn.DerEncoder.oidBasicConstraints;
import static dev.incusspawn.DerEncoder.oidSubjectAltName;
import static dev.incusspawn.DerEncoder.sha256WithRsaAid;

/**
 * A root, an intermediate it signed, and a leaf the intermediate signed for {@code host}: the shape
 * of a public CA's chain, which {@code CertificateAuthority}'s root-signed leaves are not.
 */
public record CertChain(X509Certificate root, X509Certificate intermediate, X509Certificate leaf, KeyPair leafKeys) {

    /** A fresh chain; {@code name} sets the CAs' subjects, so two chains never share one. */
    public static CertChain create(String name, String host) throws Exception {
        var rootKeys = keys();
        var intermediateKeys = keys();
        var leafKeys = keys();
        var rootName = derDistinguishedName(name + " root");
        var intermediateName = derDistinguishedName(name + " intermediate");
        var root = sign(rootName, rootName, rootKeys, rootKeys, ca());
        var intermediate = sign(rootName, intermediateName, intermediateKeys, rootKeys, ca());
        var leaf = sign(intermediateName, derDistinguishedName(host), leafKeys, intermediateKeys, concat(
                derExtension(oidBasicConstraints(), true, derSequence(new byte[0])),
                derExtension(oidSubjectAltName(), false, derSequence(derDnsName(host)))));
        return new CertChain(root, intermediate, leaf, leafKeys);
    }

    /** A local HTTPS server answering 204 that presents the leaf and the intermediate, as a public server does. */
    public HttpsServer serve() throws Exception {
        return serve(leafKeys.getPrivate(), leaf, intermediate);
    }

    /** A local HTTPS server answering 204 that presents {@code chain}, whose first certificate is {@code key}'s. */
    public static HttpsServer serve(PrivateKey key, Certificate... chain) throws Exception {
        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("leaf", key, new char[0], chain);
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);
        var tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static KeyPair keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** basicConstraints CA:TRUE, critical. */
    private static byte[] ca() {
        return derExtension(oidBasicConstraints(), true, derSequence(new byte[] {0x01, 0x01, (byte) 0xff}));
    }

    private static X509Certificate sign(byte[] issuer, byte[] subject, KeyPair subjectKeys, KeyPair issuerKeys,
                                        byte[] extensions) throws Exception {
        var notBefore = new Date(System.currentTimeMillis() - 60_000);
        var tbs = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),
                derInteger(new BigInteger(64, new SecureRandom())),
                sha256WithRsaAid(),
                issuer,
                derSequence(concat(derUtcTime(notBefore), derUtcTime(new Date(notBefore.getTime() + 86_400_000L)))),
                subject,
                subjectKeys.getPublic().getEncoded(),
                derExplicit(3, derSequence(extensions))));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(issuerKeys.getPrivate());
        signature.update(tbs);
        var der = derSequence(concat(tbs, sha256WithRsaAid(), derBitString(signature.sign())));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }
}
