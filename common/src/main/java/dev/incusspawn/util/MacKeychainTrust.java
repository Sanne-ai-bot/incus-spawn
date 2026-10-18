package dev.incusspawn.util;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * What macOS trusts for TLS beyond {@code /etc/ssl/cert.pem}: the certificates an administrator
 * (or MDM) trusts for SSL in the <em>admin</em> trust domain, which is where
 * {@code sudo security add-trusted-cert -d} and TLS-inspection agents put their CA (#1467).
 * <p>
 * Only the admin domain counts, so trusting a CA takes root, as it does on Linux. The user
 * domain (a CA a user marked "Always Trust" in their login keychain) is not read: anything
 * running as the user can ask for that, and a CA isx trusts can read what the proxy injects.
 * Apple's own roots (the system domain) are not read either; the JDK store and the PEM bundle
 * already cover the public roots, and an inspection CA is never among them.
 * <p>
 * A failure (no {@code security}, a timeout, output that does not parse) adds nothing. When only
 * the certificates cannot be read, the trust settings' denies are kept, since they need no
 * certificate bodies; when the settings themselves cannot be read, nothing is denied either.
 */
public final class MacKeychainTrust {

    /**
     * Admin-trusted certificates, the SHA-1 (upper-case hex) of every certificate an administrator
     * denied for SSL, and whether one denied the default roots as a whole (then the JDK store and
     * the bundle, which stand for them, are not trusted either).
     */
    public record Trust(List<X509Certificate> trusted, Set<String> denied, boolean defaultRootsDenied, String problem) {
        public static final Trust NONE = new Trust(List.of(), Set.of(), null);
        static Trust failed(String problem) { return new Trust(List.of(), Set.of(), problem); }

        public Trust(List<X509Certificate> trusted, Set<String> denied, String problem) {
            this(trusted, denied, false, problem);
        }

        /** Whether an administrator denied {@code cert} for SSL. */
        public boolean denies(X509Certificate cert) {
            return !denied.isEmpty() && denied.contains(sha1(cert));
        }

        /** Whether this changes nothing about what isx trusts. */
        public boolean isEmpty() {
            return trusted.isEmpty() && denied.isEmpty() && !defaultRootsDenied;
        }
    }

    /** By absolute path: a {@code security} earlier on {@code PATH} must not decide what isx trusts. */
    private static final String SECURITY = "/usr/bin/security";
    private static final String SYSTEM_KEYCHAIN = "/Library/Keychains/System.keychain";
    /** All runs together: they run at once, so a hung {@code security} holds a proxy start or a download this long at most. */
    private static final long TIMEOUT_SECONDS = 5;
    /** The trustList key for the settings of the default (system) roots as a whole, not a certificate. */
    private static final String DEFAULT_ROOTS_KEY = "kSecTrustSettingsDefaultRootCertSetting";
    private static final java.util.regex.Pattern SHA1_HEX = java.util.regex.Pattern.compile("[0-9A-Fa-f]{40}");

    /** Content octets of the OID 1.2.840.113635.100.1.3, Apple's SSL policy, as a trust setting stores it. */
    private static final byte[] SSL_POLICY_OID = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x63, 0x64, 0x01, 0x03};
    /** {@code kSecTrustSettingsKeyUseAny}: a key-usage constraint that constrains nothing. */
    private static final long KEY_USE_ANY = 0xffffffffL;

    private static final int RESULT_TRUST_ROOT = 1;
    private static final int RESULT_TRUST_AS_ROOT = 2;
    private static final int RESULT_DENY = 3;

    private MacKeychainTrust() {}

    /** Reads the admin domain's trust settings and the keychains' certificates. Never throws. */
    public static Trust read() {
        var temps = new ArrayList<Path>();
        var running = new ArrayList<Process>();
        try {
            // A file the user alone can read: trust-settings-export takes no stdout
            var export = temp(temps, "isx-trust-settings", ".plist");
            // Certificate bodies come from the user's login keychain too: `add-trusted-cert -d` without
            // -k stores a certificate there. That trusts nothing by itself; only the admin domain's
            // settings, which take root to write, decide what is trusted.
            // The login keychain is read on its own, so one a user-level process broke cannot fail the
            // System keychain's read too.
            var login = Path.of(System.getProperty("user.home"), "Library", "Keychains", "login.keychain-db");
            // Started together, so the wait is the slowest one's, not the sum
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            var exporting = start(temps, running, deadline, SECURITY, "trust-settings-export", "-d", export.toString());
            var findingSystem = start(temps, running, deadline, SECURITY, "find-certificate", "-a", "-p", SYSTEM_KEYCHAIN);
            var findingLogin = Files.isRegularFile(login)
                    ? start(temps, running, deadline, SECURITY, "find-certificate", "-a", "-p", login.toString()) : null;
            var exported = exporting.finish();
            if (exported.exit() != 0) {
                // With no admin trust settings at all, there is nothing to trust or deny
                if (exported.output().contains("No Trust Settings")) return Trust.NONE;
                return Trust.failed("security trust-settings-export exited " + exported.exit() + ": " + exported.output().strip());
            }
            return evaluate(Files.readString(export), () -> certificatesOf(findingSystem), loginCertificates(findingLogin));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Trust.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            // One that timed out was ended already; the other must not outlive the failure
            running.forEach(Process::destroyForcibly);
            for (var t : temps) {
                try {
                    Files.deleteIfExists(t);
                } catch (IOException ignored) {
                    // A leftover holds only public certificates and trust settings
                }
            }
        }
    }

    /**
     * The login keychain's certificates, or none when it is absent or its read fails in any way (a
     * timeout or output that does not parse included): that only loses bodies stored there, never
     * the System keychain's or the export's denies, so a keychain the user can break cannot cancel them.
     */
    private static List<X509Certificate> loginCertificates(Running finding) {
        if (finding == null) return List.of();
        try {
            return certificatesOf(finding);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** A {@code find-certificate} run's certificates; throws when it fails, times out or prints something else. */
    private static List<X509Certificate> certificatesOf(Running finding) throws Exception {
        var run = finding.finish();
        if (run.exit() != 0) throw new IOException("exited " + run.exit() + ": " + run.output().strip());
        return certificates(run.output());
    }

    interface Certificates {
        List<X509Certificate> get() throws Exception;
    }

    /**
     * {@link #evaluate(String, List)} over the System keychain's certificates and the login
     * keychain's. When the System keychain's cannot be had, whatever the failure, the settings'
     * denies still apply (they need no certificate bodies) and nothing is trusted.
     */
    static Trust evaluate(String trustSettingsXml, Certificates systemKeychain, List<X509Certificate> loginKeychain) throws Exception {
        List<X509Certificate> certs;
        try {
            certs = new ArrayList<>(systemKeychain.get());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            var denies = evaluate(trustSettingsXml, List.of());
            return new Trust(List.of(), denies.denied(), denies.defaultRootsDenied(),
                    "security find-certificate on the System keychain: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        certs.addAll(loginKeychain);
        return evaluate(trustSettingsXml, certs);
    }

    private static List<X509Certificate> certificates(String pem) throws java.security.cert.CertificateException {
        return SystemTrust.certificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static Path temp(List<Path> temps, String prefix, String suffix) throws IOException {
        var t = Files.createTempFile(prefix, suffix);
        temps.add(t);
        return t;
    }

    private record Run(int exit, String output) {}

    private record Running(Process process, Path out, String name, long deadline) {
        /** Waits until the deadline the runs share, so all of them together take {@link #TIMEOUT_SECONDS} at most. */
        Run finish() throws IOException, InterruptedException {
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly();
                throw new IOException("security " + name + " timed out after " + TIMEOUT_SECONDS + "s");
            }
            return new Run(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8));
        }
    }

    private static Running start(List<Path> temps, List<Process> running, long deadline, String... command) throws IOException {
        // Output to a file, not a pipe: a hung command is then ended by the timeout, never by a blocked read
        var out = temp(temps, "isx-security", ".out");
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .start();
        running.add(process);
        return new Running(process, out, command[1], deadline);
    }

    /**
     * Applies the admin domain's trust settings ({@code trust-settings-export} XML) to the
     * keychains' certificates. Throws on output that is not such a plist.
     */
    static Trust evaluate(String trustSettingsXml, List<X509Certificate> keychains) throws Exception {
        var plist = parsePlist(trustSettingsXml);
        if (!(plist instanceof Map<?, ?> root) || !(root.get("trustList") instanceof Map<?, ?> trustList)) {
            throw new IOException("trust settings have no trustList");
        }
        var bySha1 = new HashMap<String, X509Certificate>();
        for (var cert : keychains) bySha1.putIfAbsent(sha1(cert), cert);

        var trusted = new ArrayList<X509Certificate>();
        var denied = new TreeSet<String>();
        boolean defaultRootsDenied = false;
        for (var entry : trustList.entrySet()) {
            var key = entry.getKey().toString();
            if (!(entry.getValue() instanceof Map<?, ?> record)) continue;
            if (DEFAULT_ROOTS_KEY.equals(key)) {
                defaultRootsDenied = decide(record.get("trustSettings")) == Decision.DENY;
                continue;
            }
            if (!SHA1_HEX.matcher(key).matches()) continue;
            var sha1 = key.toUpperCase(Locale.ROOT);
            switch (decide(record.get("trustSettings"))) {
                case TRUST -> {
                    var cert = bySha1.get(sha1);
                    if (cert != null && sameIssuerAndSerial(record, cert)) trusted.add(cert);
                }
                case DENY -> denied.add(sha1);
                case NONE -> {}
            }
        }
        return new Trust(List.copyOf(trusted), Set.copyOf(denied), defaultRootsDenied, null);
    }

    /**
     * Whether the record names {@code cert} by issuer and serial number as well as by SHA-1, when it
     * carries them (an export always does): a certificate body read from a keychain the user can
     * write is then bound to the record by more than one hash.
     */
    private static boolean sameIssuerAndSerial(Map<?, ?> record, X509Certificate cert) {
        var issuer = record.get("issuerName");
        var serial = record.get("serialNumber");
        if (issuer != null && !(issuer instanceof byte[] i && Arrays.equals(i, cert.getIssuerX500Principal().getEncoded()))) return false;
        return serial == null || serial instanceof byte[] n && Arrays.equals(n, cert.getSerialNumber().toByteArray());
    }

    enum Decision { TRUST, DENY, NONE }

    /**
     * What one certificate's trust settings say about a TLS server certificate for any host,
     * evaluated as macOS does: the first entry that applies decides, an empty or absent list trusts as root,
     * and an entry with no result trusts as root. Wherever an entry applies only to some TLS
     * connections, the answer is the narrower one: a constrained trust entry is skipped, while a
     * constrained deny denies. An entry this cannot read (not a dict, a result that is not an
     * integer, or a policy that is not data) decides nothing, but it taints the list: a later deny
     * still denies, a later trust does not trust, since macOS may have read that entry as a deny.
     */
    static Decision decide(Object trustSettings) {
        // `add-trusted-cert -d -r trustRoot` with no policy exports no settings at all, which macOS
        // reads as an empty list: trusted as root (recorded on a runner, #1515)
        if (trustSettings == null) return Decision.TRUST;
        if (!(trustSettings instanceof List<?> entries)) return Decision.NONE;
        if (entries.isEmpty()) return Decision.TRUST;
        boolean unreadable = false;
        for (var e : entries) {
            if (!(e instanceof Map<?, ?> entry)) {
                unreadable = true;
                continue;
            }
            var policy = entry.get("kSecTrustSettingsPolicy");
            // A policy this cannot read may be SSL's: such an entry still denies, but taints the list
            boolean policyUnreadable = policy != null && !(policy instanceof byte[]);
            if (policy instanceof byte[] oid && !Arrays.equals(oid, SSL_POLICY_OID)) continue;
            // Only an absent result means trusted as root; macOS reads any number, so a <real>3</real> denies
            var value = entry.get("kSecTrustSettingsResult");
            if (value instanceof Long r && r == RESULT_DENY || value instanceof Double d && d == RESULT_DENY) return Decision.DENY;
            if (policyUnreadable || value != null && !(value instanceof Long)) {
                unreadable = true;
                continue;
            }
            long result = value instanceof Long r ? r : RESULT_TRUST_ROOT;
            if (isConstrained(entry) || unreadable) continue;
            if (result == RESULT_TRUST_ROOT || result == RESULT_TRUST_AS_ROOT) return Decision.TRUST;
            // kSecTrustSettingsResultUnspecified, or a value this does not know: the next entry decides
        }
        return Decision.NONE;
    }

    /** Restricted to a host name, an application or a key usage: it applies to some TLS connections only. */
    private static boolean isConstrained(Map<?, ?> entry) {
        return entry.containsKey("kSecTrustSettingsPolicyString")
                || entry.containsKey("kSecTrustSettingsApplication")
                || (entry.containsKey("kSecTrustSettingsKeyUsage")
                    && !(entry.get("kSecTrustSettingsKeyUsage") instanceof Long usage && (usage & KEY_USE_ANY) == KEY_USE_ANY));
    }

    /** SHA-1 of the certificate's encoding, upper-case hex: how trust settings name a certificate. */
    public static String sha1(X509Certificate cert) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- XML property lists ---

    /**
     * Parses an XML property list. Its DOCTYPE is dropped before parsing and any other is refused,
     * so no DTD or entity is ever resolved.
     */
    static Object parsePlist(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setNamespaceAware(false);
        var withoutDoctype = xml.replaceFirst("<!DOCTYPE plist [^>]*>", "");
        var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(withoutDoctype)));
        var root = doc.getDocumentElement();
        if (!"plist".equals(root.getTagName())) throw new IOException("not a property list: <" + root.getTagName() + ">");
        var values = children(root);
        if (values.size() != 1) throw new IOException("a property list holds one value, found " + values.size());
        return value(values.getFirst());
    }

    private static Object value(Element e) throws IOException {
        return switch (e.getTagName()) {
            case "dict" -> {
                var map = new LinkedHashMap<String, Object>();
                var items = children(e);
                if (items.size() % 2 != 0) throw new IOException("a dict with a key and no value");
                for (int i = 0; i < items.size(); i += 2) {
                    var key = items.get(i);
                    if (!"key".equals(key.getTagName())) throw new IOException("expected <key>, found <" + key.getTagName() + ">");
                    map.put(key.getTextContent(), value(items.get(i + 1)));
                }
                yield map;
            }
            case "array" -> {
                var list = new ArrayList<>();
                for (var item : children(e)) list.add(value(item));
                yield list;
            }
            case "string", "date" -> e.getTextContent();
            case "integer" -> {
                try {
                    yield Long.parseLong(e.getTextContent().strip());
                } catch (NumberFormatException ex) {
                    throw new IOException("not an integer: " + e.getTextContent().strip());
                }
            }
            case "real" -> Double.parseDouble(e.getTextContent().strip());
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "data" -> Base64.getMimeDecoder().decode(e.getTextContent().replaceAll("\\s", ""));
            default -> throw new IOException("unknown property list element <" + e.getTagName() + ">");
        };
    }

    private static List<Element> children(Element parent) {
        var elements = new ArrayList<Element>();
        for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) elements.add((Element) n);
        }
        return elements;
    }
}
