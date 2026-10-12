package dev.incusspawn.proxy;

import dev.incusspawn.DerEncoder;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static dev.incusspawn.DerEncoder.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Covers the upgrade of CA certificates generated before Subject Key Identifiers
 * were added — the case every existing install lands in, and the one CI's
 * always-fresh {@code isx init} never exercises.
 */
class CertificateAuthorityTest {

    @TempDir
    Path tempHome;

    private String savedHome;

    private final Path savedAnchorsDir = CertificateAuthority.hostAnchorsDir;

    @BeforeEach
    void redirectHome() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        CertificateAuthority.hostAnchorsDir = tempHome.resolve("host-anchors");
    }

    @AfterEach
    void restoreHome() {
        if (savedHome != null) System.setProperty("user.home", savedHome);
        CertificateAuthority.hostAnchorsDir = savedAnchorsDir;
    }

    @Test
    void freshCaHasSki() {
        var ca = CertificateAuthority.loadOrCreate();
        assertNotNull(ca.caCert().getExtensionValue(DerEncoder.SKI_OID),
                "a newly generated CA cert must carry a Subject Key Identifier");
        assertEquals("", CertificateAuthority.supersededCaFingerprint(),
                "a CA that was never re-issued has no superseded fingerprint");
    }

    @Test
    void legacyCaIsReissuedOverTheSameKey() throws Exception {
        var legacyCert = writeLegacyCa();

        var ca = CertificateAuthority.loadOrCreate();
        var upgraded = ca.caCert();

        assertNotNull(upgraded.getExtensionValue(DerEncoder.SKI_OID),
                "the re-issued CA cert must carry a Subject Key Identifier");
        assertArrayEquals(legacyCert.getPublicKey().getEncoded(), upgraded.getPublicKey().getEncoded(),
                "the CA key pair must be reused, so existing leaf certs stay valid");
        assertEquals(legacyCert.getSubjectX500Principal(), upgraded.getSubjectX500Principal());
        assertEquals(legacyCert.getNotBefore(), upgraded.getNotBefore(),
                "re-issuing must not reset the validity window");
        assertEquals(legacyCert.getNotAfter(), upgraded.getNotAfter());
        // Self-signed roots are exempt from AKI (RFC 5280 §4.2.1.1) and OpenSSL's
        // strict mode only demands SKI of them.
        assertNull(upgraded.getExtensionValue(DerEncoder.AKI_OID));
        upgraded.verify(upgraded.getPublicKey());
    }

    @Test
    void reissuedCaIsPersistedAndSupersedesTheOldOne() throws Exception {
        var legacyCert = writeLegacyCa();
        var legacyFp = fingerprint(legacyCert);

        var ca = CertificateAuthority.loadOrCreate();

        assertEquals(legacyFp, CertificateAuthority.supersededCaFingerprint(),
                "the replaced cert must be recorded so images built against it are recognised");
        assertNotEquals(legacyFp, ca.caFingerprint());
        // caCertPem() reads from disk while caFingerprint() reads memory: containers must
        // not be handed a cert that disagrees with the fingerprint stamped on them.
        var onDisk = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(ca.caCertPem().getBytes()));
        assertEquals(ca.caFingerprint(), fingerprint(onDisk));
    }

    @Test
    void reissueIsDeterministicAndIdempotent() throws Exception {
        writeLegacyCa();

        var first = CertificateAuthority.loadOrCreate().caFingerprint();
        var second = CertificateAuthority.loadOrCreate().caFingerprint();

        assertEquals(first, second,
                "re-issuing is a pure function of the old cert and key, so concurrent "
                        + "migrations converge and a second load is a no-op");
    }

    @Test
    void trustSnapshotClassifiesStoredFingerprints() throws Exception {
        var legacyFp = fingerprint(writeLegacyCa());
        var stamp = CertificateAuthority.collectTrustAnchors().stamp();

        var trust = CertificateAuthority.CaTrust.snapshot();
        assertEquals(CertificateAuthority.CaStatus.CURRENT, trust.classify(stamp));
        assertEquals(CertificateAuthority.CaStatus.REPAIRABLE, trust.classify(legacyFp),
                "an image built against the cert we re-issued is repairable, not foreign");
        assertEquals(CertificateAuthority.CaStatus.FOREIGN, trust.classify("deadbeef"));
        assertEquals(CertificateAuthority.CaStatus.UNKNOWN, trust.classify(""));
        assertEquals(CertificateAuthority.CaStatus.UNKNOWN, trust.classify(null));
    }

    @Test
    void withoutHostAnchorsTheStampIsTheCasFingerprint() {
        // As every image was stamped before host anchors: those images stay current, and none
        // is reconciled on its first use after the upgrade
        var ca = CertificateAuthority.loadOrCreate();
        assertEquals(ca.caFingerprint(), CertificateAuthority.collectTrustAnchors().stamp());
    }

    @Test
    void changedHostAnchorsLeaveAnImageBuiltAgainstThisCaBranchable() throws Exception {
        propagateHostAnchors();
        var ca = CertificateAuthority.loadOrCreate();
        writeHostAnchor("corp-a.crt", selfSigned("corp root a"));
        var built = CertificateAuthority.collectTrustAnchors().stamp();

        writeHostAnchor("corp-b.crt", selfSigned("corp root b"));
        var now = CertificateAuthority.collectTrustAnchors().stamp();

        assertNotEquals(built, now, "the instance must be reconciled to the new set");
        assertTrue(built.startsWith(ca.caFingerprint() + "+"), built);
        assertEquals(CertificateAuthority.CaStatus.CURRENT, CertificateAuthority.CaTrust.snapshot().classify(built),
                "a template built against this CA is not foreign because a host anchor was added");
    }

    @Test
    void anImageBuiltWithHostAnchorsAgainstTheSupersededCaIsRepairable() throws Exception {
        var legacyFp = fingerprint(writeLegacyCa());
        assertEquals(CertificateAuthority.CaStatus.REPAIRABLE,
                CertificateAuthority.CaTrust.snapshot().classify(legacyFp + "+0123abcd"));
    }

    @Test
    void rotatingTheCaClearsTheSupersededRecord() throws Exception {
        writeLegacyCa();
        var upgraded = CertificateAuthority.loadOrCreate();
        assertNotEquals("", CertificateAuthority.supersededCaFingerprint());

        // A genuine rotation: drop the CA so a new key pair is generated.
        Files.delete(SpawnConfig.configDir().resolve("ca.crt"));
        Files.delete(SpawnConfig.configDir().resolve("ca.key"));
        var rotated = CertificateAuthority.loadOrCreate();

        assertNotEquals(upgraded.caFingerprint(), rotated.caFingerprint());
        assertEquals("", CertificateAuthority.supersededCaFingerprint(),
                "a rotation must not leave a record that makes stale images look repairable");
    }

    @Test
    void corruptCaCertSurfacesAsAnError() throws Exception {
        writeLegacyCa();
        var certFile = SpawnConfig.configDir().resolve("ca.crt");
        Files.writeString(certFile, "-----BEGIN CERTIFICATE-----\nnot a cert\n-----END CERTIFICATE-----\n");

        assertThrows(RuntimeException.class, CertificateAuthority::loadOrCreate,
                "a corrupt CA must surface as an error, not be silently regenerated");
        assertEquals("", CertificateAuthority.supersededCaFingerprint(),
                "nothing was superseded, so no backup must be left behind");
    }

    // --- Trust anchors ---

    @Test
    void collectTrustAnchorsIncludesMitmCa() {
        var ca = CertificateAuthority.loadOrCreate();
        var anchors = CertificateAuthority.collectTrustAnchors().files();
        var expectedKey = "isx-" + ca.caFingerprint() + ".crt";
        assertTrue(anchors.containsKey(expectedKey),
                "trust anchors must include the MITM CA keyed by its DER fingerprint");
        assertEquals(ca.caCertPem().strip(), anchors.get(expectedKey));
    }

    @Test
    void collectTrustAnchorsCreatesTheCaWhenThereIsNone() {
        // A build installs the set into a fresh image: it must hold the CA the proxy will sign with
        assertFalse(CertificateAuthority.exists());
        var trust = CertificateAuthority.collectTrustAnchors();
        assertTrue(CertificateAuthority.exists());
        assertEquals(CertificateAuthority.currentCaFingerprint(), trust.caFingerprint());
    }

    @Test
    void hostAnchorsAreKeyedBySha256AndInvalidOnesSkipped() throws Exception {
        propagateHostAnchors();
        var ca = CertificateAuthority.loadOrCreate();
        var corp = selfSigned("corp root");
        writeHostAnchor("Corp Root CA.crt", corp);
        writeHostAnchor("x;touch pwned;.pem", corp);
        writeHostAnchor("garbage.crt", "not a certificate");
        writeHostAnchor("readme.txt", corp);
        writeHostAnchor("local.crt", ca.caCertPem());

        var files = CertificateAuthority.collectTrustAnchors().files();
        assertEquals(java.util.Set.of("isx-" + ca.caFingerprint() + ".crt", "isx-" + fingerprint(parse(corp)) + ".crt"),
                files.keySet(), "the host's file names never reach the guest");
    }

    @Test
    void onlyReEncodedCertificatesReachTheGuest() throws Exception {
        // The guest gets what the host's files hold as certificates, never the files: a key
        // saved beside a cert would otherwise be world-readable in every instance
        propagateHostAnchors();
        var ca = CertificateAuthority.loadOrCreate();
        var corp = selfSigned("corp root");
        // Generated, not a literal: a PEM key block in the source is what secret scanners flag
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var key = toPem("PRIVATE KEY", keyGen.generateKeyPair().getPrivate().getEncoded());
        writeHostAnchor("with-key.pem", corp + key);
        var a = selfSigned("bundle root a");
        var b = selfSigned("bundle root b");
        writeHostAnchor("bundle.pem", "Bag Attributes\r\n    friendlyName: corp\r\n" + a.replace("\n", "\r\n") + b);

        var files = CertificateAuthority.collectTrustAnchors().files();
        for (var content : files.values()) {
            assertFalse(content.contains("PRIVATE KEY"), content);
            assertFalse(content.contains("Bag Attributes"), content);
            assertFalse(content.contains("\r"), "re-encoded, not copied");
        }
        assertEquals(java.util.Set.of("isx-" + ca.caFingerprint() + ".crt",
                        "isx-" + fingerprint(parse(a)) + ".crt", "isx-" + fingerprint(parse(b)) + ".crt"),
                files.keySet(), "each certificate of a bundle is an anchor; a file holding a key is refused");
        assertEquals(DerEncoder.toPem("CERTIFICATE", parse(b).getEncoded()).strip(),
                files.get("isx-" + fingerprint(parse(b)) + ".crt").strip());
    }

    @Test
    void theStampChangesOnlyWithTheCertificates() throws Exception {
        propagateHostAnchors();
        var corp = selfSigned("corp root");
        writeHostAnchor("corp.pem", corp);
        var before = CertificateAuthority.collectTrustAnchors().stamp();
        writeHostAnchor("corp.pem", "# corporate root\r\n" + corp.replace("\n", "\r\n"));
        assertEquals(before, CertificateAuthority.collectTrustAnchors().stamp(),
                "comments and line endings are not a different trust set");
    }

    @Test
    void hostAnchorsAreIgnoredUnlessPropagationIsOn() throws Exception {
        CertificateAuthority.loadOrCreate();
        writeHostAnchor("corp.crt", selfSigned("corp root"));
        assertEquals(1, CertificateAuthority.collectTrustAnchors().files().size());
    }

    @Test
    void theStampIsDeterministic() throws Exception {
        propagateHostAnchors();
        writeHostAnchor("corp.crt", selfSigned("corp root"));
        assertEquals(CertificateAuthority.collectTrustAnchors().stamp(),
                CertificateAuthority.collectTrustAnchors().stamp());
    }

    @Test
    void buildReconcileScriptCleansOldNamesAndWritesNewOnes() {
        var anchors = new TreeMap<String, String>();
        anchors.put("isx-abc.crt", "-----BEGIN CERTIFICATE-----\nFAKE\n-----END CERTIFICATE-----");
        var script = CertificateAuthority.buildReconcileScript(anchors);
        assertTrue(script.contains("rm -f"));
        assertTrue(script.contains("incus-spawn-mitm.crt"));
        assertTrue(script.contains("isx-*.crt"));
        assertTrue(script.contains("isx-abc.crt"));
        assertTrue(script.contains("update-ca-trust"));
        assertTrue(script.startsWith("set -e\n"), "a failed write must fail the script");
    }

    @Test
    void aFailedReconcileIsRetriedEvenIfTheTrustSetChangesBack() {
        // The guest's anchors were removed before the install failed: whatever the stamp says
        // now, the next check must not take it for a guest that holds a trust set. Empty means
        // "built before stamps" and is skipped, so it would never be repaired.
        CertificateAuthority.loadOrCreate();
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.CA_FINGERPRINT, "deadbeef"));
        var incus = spy(daemon.client());
        doReturn(new IncusClient.ExecResult(1, "", "update-ca-trust: failed"))
                .when(incus).shellExec(eq("dev-1"), any(String[].class));

        assertFalse(CertificateAuthority.fixContainerCaIfNeeded(incus, "dev-1"));

        var stamp = incus.configGet("dev-1", Metadata.CA_FINGERPRINT);
        assertFalse(stamp.isEmpty(), "an empty stamp is never checked again");
        assertNotEquals("deadbeef", stamp, "the old set's stamp would match it again");
        assertNotEquals(CertificateAuthority.collectTrustAnchors().stamp(), stamp,
                "the guest does not hold the current set");
        assertEquals(CertificateAuthority.CaStatus.CURRENT, CertificateAuthority.CaTrust.snapshot().classify(stamp),
                "the instance was being given this host's CA: branching from it is not refused");
    }

    // --- Helpers ---

    /** Write a CA key + cert in the pre-SKI shape: basicConstraints + keyUsage only. */
    private X509Certificate writeLegacyCa() throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();
        var notBefore = new Date(System.currentTimeMillis() - 86400_000L);
        var expiry = new Date(notBefore.getTime() + 3650L * 24 * 60 * 60 * 1000);
        var serial = new BigInteger(128, new SecureRandom());
        var subject = derDistinguishedName("incus-spawn MITM CA");
        var keyUsageBits = new byte[]{0x03, 0x02, 0x01, 0x06};
        var algId = sha256WithRsaAid();

        var tbsCert = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),
                derInteger(serial),
                algId,
                subject,
                derSequence(concat(derUtcTime(notBefore), derUtcTime(expiry))),
                subject,
                keyPair.getPublic().getEncoded(),
                derExplicit(3, derSequence(concat(
                        derExtension(oidBasicConstraints(), true,
                                derSequence(new byte[]{0x01, 0x01, (byte) 0xff})),
                        derExtension(oidKeyUsage(), true, keyUsageBits)
                )))
        ));
        var sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbsCert);
        var certDer = derSequence(concat(tbsCert, algId, derBitString(sig.sign())));

        var dir = SpawnConfig.configDir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ca.key"), toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        Files.writeString(dir.resolve("ca.crt"), toPem("CERTIFICATE", certDer));

        var cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        assertNull(cert.getExtensionValue(DerEncoder.SKI_OID),
                "sanity: the handcrafted legacy CA must have no SKI");
        return cert;
    }

    private static void propagateHostAnchors() throws Exception {
        Files.createDirectories(SpawnConfig.configDir());
        Files.writeString(SpawnConfig.configDir().resolve("config.yaml"), "host-ca:\n  propagate: true\n");
    }

    private static void writeHostAnchor(String name, String content) throws Exception {
        Files.createDirectories(CertificateAuthority.hostAnchorsDir);
        Files.writeString(CertificateAuthority.hostAnchorsDir.resolve(name), content);
    }

    /** A self-signed CA certificate, PEM-encoded, standing in for a corporate root. */
    private static String selfSigned(String cn) throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();
        var notBefore = new Date(System.currentTimeMillis() - 86400_000L);
        var expiry = new Date(notBefore.getTime() + 3650L * 24 * 60 * 60 * 1000);
        var algId = sha256WithRsaAid();
        var subject = derDistinguishedName(cn);
        var tbsCert = derSequence(concat(
                derExplicit(0, derInteger(BigInteger.valueOf(2))),
                derInteger(new BigInteger(128, new SecureRandom())),
                algId,
                subject,
                derSequence(concat(derUtcTime(notBefore), derUtcTime(expiry))),
                subject,
                keyPair.getPublic().getEncoded(),
                derExplicit(3, derSequence(derExtension(oidBasicConstraints(), true,
                        derSequence(new byte[]{0x01, 0x01, (byte) 0xff}))))
        ));
        var sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbsCert);
        return toPem("CERTIFICATE", derSequence(concat(tbsCert, algId, derBitString(sig.sign()))));
    }

    private static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String fingerprint(X509Certificate cert) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }
}
