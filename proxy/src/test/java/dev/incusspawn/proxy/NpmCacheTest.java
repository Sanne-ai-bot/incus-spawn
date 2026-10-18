package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.incusspawn.proxy.ArtifactCacheHandler.SidecarAnswer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NpmCacheTest {

    @Test
    void npmDomainsAreIntercepted() {
        for (var domain : ProxyConfig.NPM_DOMAINS) {
            assertTrue(ProxyConfig.isInterceptedDomain(domain, ProxyConfig.builtinInterceptedDomains(), java.util.List.of()),
                    domain + " should be intercepted");
            assertTrue(ProxyConfig.builtinInterceptedDomains().contains(domain),
                    domain + " should be in interceptedDomains()");
        }
    }

    // --- tarball pattern ---

    @Test
    void tarballPatternMatchesScopedPackage() {
        var m = ArtifactCacheHandler.NPM_TARBALL_PATTERN.matcher("/@openai/codex/-/codex-0.1.0.tgz");
        assertTrue(m.matches());
        assertEquals("@openai/codex/-/codex-0.1.0.tgz", m.group(1));
    }

    @Test
    void tarballPatternMatchesUnscopedPackage() {
        var m = ArtifactCacheHandler.NPM_TARBALL_PATTERN.matcher("/express/-/express-4.18.2.tgz");
        assertTrue(m.matches());
        assertEquals("express/-/express-4.18.2.tgz", m.group(1));
    }

    @Test
    void tarballPatternMatchesPreReleaseVersion() {
        var m = ArtifactCacheHandler.NPM_TARBALL_PATTERN.matcher("/@types/node/-/node-20.0.0-beta.1.tgz");
        assertTrue(m.matches());
        assertEquals("@types/node/-/node-20.0.0-beta.1.tgz", m.group(1));
    }

    @Test
    void tarballPatternRejectsMetadataPath() {
        assertFalse(ArtifactCacheHandler.NPM_TARBALL_PATTERN.matcher("/@openai/codex").matches());
        assertFalse(ArtifactCacheHandler.NPM_TARBALL_PATTERN.matcher("/express").matches());
    }

    @Test
    void tarballPatternRejectsSearchPath() {
        assertFalse(ArtifactCacheHandler.NPM_TARBALL_PATTERN.matcher("/-/v1/search?text=express").matches());
    }

    @Test
    void tarballPatternRejectsPathWithoutVersionDigit() {
        assertFalse(ArtifactCacheHandler.NPM_TARBALL_PATTERN.matcher("/express/-/express-latest.tgz").matches());
    }

    @Test
    void tarballPatternAllowsDotDotButContainmentCheckBlocks() {
        var m = ArtifactCacheHandler.NPM_TARBALL_PATTERN.matcher("/../-/..-1.0.tgz");
        assertTrue(m.matches(), "regex matches (containment check is elsewhere)");
        assertEquals("../-/..-1.0.tgz", m.group(1));
    }

    // --- path traversal ---

    @Test
    void storePackageEtagRejectsTraversal(@TempDir Path tmp) throws Exception {
        var origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            ArtifactCacheHandler.storePackageEtag("../../etc", "\"evil\"");

            var escaped = tmp.resolve(".cache/incus-spawn/etc/.etag");
            assertFalse(Files.exists(escaped), "should not write outside npm cache dir");

            var legitimate = tmp.resolve(".cache/incus-spawn/npm/../../etc/.etag");
            assertFalse(Files.exists(legitimate.normalize()),
                    "should not write anywhere when path escapes");
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    // --- packument pattern ---

    @Test
    void packumentPatternMatchesUnscopedPackage() {
        var m = ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/express");
        assertTrue(m.matches());
        assertEquals("express", m.group(1));
    }

    @Test
    void packumentPatternMatchesScopedPackage() {
        var m = ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/@openai/codex");
        assertTrue(m.matches());
        assertEquals("@openai/codex", m.group(1));
    }

    @Test
    void packumentPatternMatchesDottedPackageName() {
        var m = ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/socket.io");
        assertTrue(m.matches());
        assertEquals("socket.io", m.group(1));
    }

    @Test
    void packumentPatternRejectsTarballPath() {
        assertFalse(ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/@openai/codex/-/codex-0.1.0.tgz").matches());
        assertFalse(ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/express/-/express-4.18.2.tgz").matches());
    }

    @Test
    void packumentPatternRejectsPerVersionPath() {
        assertFalse(ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/express/4.18.2").matches());
        assertFalse(ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/@openai/codex/0.1.0").matches());
    }

    @Test
    void packumentPatternRejectsSpecialPaths() {
        assertFalse(ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/-/v1/search").matches());
        assertFalse(ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/-/npm/v1/security/advisories").matches());
    }

    @Test
    void packumentPatternRejectsRootPath() {
        assertFalse(ArtifactCacheHandler.NPM_PACKUMENT_PATTERN.matcher("/").matches());
    }

    // --- package ref parsing ---

    @Test
    void parseScopedPackagePath() {
        var ref = ArtifactCacheHandler.parseNpmTarballPath("@openai/codex/-/codex-0.1.0.tgz");
        assertNotNull(ref);
        assertEquals("@openai/codex", ref.packageName());
        assertEquals("0.1.0", ref.version());
    }

    @Test
    void parseUnscopedPackagePath() {
        var ref = ArtifactCacheHandler.parseNpmTarballPath("express/-/express-4.18.2.tgz");
        assertNotNull(ref);
        assertEquals("express", ref.packageName());
        assertEquals("4.18.2", ref.version());
    }

    @Test
    void parsePreReleaseVersion() {
        var ref = ArtifactCacheHandler.parseNpmTarballPath("@types/node/-/node-20.0.0-beta.1.tgz");
        assertNotNull(ref);
        assertEquals("@types/node", ref.packageName());
        assertEquals("20.0.0-beta.1", ref.version());
    }

    @Test
    void parseHyphenatedPackageName() {
        var ref = ArtifactCacheHandler.parseNpmTarballPath("es5-ext/-/es5-ext-0.10.62.tgz");
        assertNotNull(ref);
        assertEquals("es5-ext", ref.packageName());
        assertEquals("0.10.62", ref.version());
    }

    @Test
    void parseReturnsNullForInvalidPath() {
        assertNull(ArtifactCacheHandler.parseNpmTarballPath("express"));
        assertNull(ArtifactCacheHandler.parseNpmTarballPath("express/-/e.tgz"));
    }

    // --- digest verification (OCI blobs, npm tarballs) ---

    /** {@code sha512:} of "hello". */
    static final String HELLO_SHA512 = "sha512:9b71d224bd62f3785d96d46ad3ea3d73319bfbc2890caadae2dff72519673ca7"
            + "2323c3d99ba5c11d7c7acc6e14b8c5da0c4663475c2e5c3adef46f73bcdec043";

    /** Whether a download of "hello" is committed under the given digest, as fetchCacheAndServe would. */
    static boolean commits(Path tmp, String digest) throws Exception {
        var download = tmp.resolve("download");
        Files.writeString(download, "hello");
        var verification = ArtifactCacheHandler.Verification.ofDigest(digest);
        var expected = verification.expected().apply(null).result();
        return expected != null && VerifiedArtifactStore.verifyAndCommit(
                download, tmp.resolve("blob"), verification.checksum(), expected, false);
    }

    @Test
    void digestSha256(@TempDir Path tmp) throws Exception {
        assertTrue(commits(tmp, "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
        assertFalse(commits(tmp, "sha256:0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void digestSha1(@TempDir Path tmp) throws Exception {
        assertTrue(commits(tmp, "sha1:aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"));
        assertFalse(commits(tmp, "sha1:0000000000000000000000000000000000000000"));
    }

    @Test
    void digestSha512(@TempDir Path tmp) throws Exception {
        assertTrue(commits(tmp, HELLO_SHA512));
        assertFalse(commits(tmp, "sha512:" + "0".repeat(128)));
    }

    @Test
    void digestWithUnknownAlgorithmIsNeverCommitted(@TempDir Path tmp) throws Exception {
        assertFalse(commits(tmp, "md5:5d41402abc4b2a76b9719d911017c592"));
        assertFalse(Files.exists(tmp.resolve("blob")));
    }

    // --- readFileOrNull ---

    @Test
    void readFileOrNullReturnsStrippedContent(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("data.txt");
        Files.writeString(f, "  hello world  \n");
        assertEquals("hello world", ArtifactCacheHandler.readFileOrNull(f));
    }

    @Test
    void readFileOrNullReturnsNullForMissing(@TempDir Path tmp) {
        assertNull(ArtifactCacheHandler.readFileOrNull(tmp.resolve("nope.txt")));
    }

    @Test
    void readFileOrNullReturnsEmptyStringForEmptyFile(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("empty.txt");
        Files.writeString(f, "");
        assertEquals("", ArtifactCacheHandler.readFileOrNull(f));
    }

    // --- sidecar file writing ---

    @Test
    void writeNpmSidecarFilesCreatesShasumAndEtag(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("express/-/express-4.18.2.tgz");
        ArtifactCacheHandler.writeNpmSidecarFiles(cacheFile, "abc123def456", "\"etag-value\"");

        assertEquals("abc123def456",
                Files.readString(Path.of(cacheFile + ".shasum")).strip());
        assertEquals("\"etag-value\"",
                Files.readString(Path.of(cacheFile + ".etag")).strip());
    }

    @Test
    void writeNpmSidecarFilesSkipsEtagWhenNull(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        ArtifactCacheHandler.writeNpmSidecarFiles(cacheFile, "deadbeef", null);

        assertTrue(Files.isRegularFile(Path.of(cacheFile + ".shasum")));
        assertFalse(Files.exists(Path.of(cacheFile + ".etag")));
    }

    @Test
    void writeNpmSidecarFilesOverwritesExisting(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        ArtifactCacheHandler.writeNpmSidecarFiles(cacheFile, "old-shasum", "\"old-etag\"");
        ArtifactCacheHandler.writeNpmSidecarFiles(cacheFile, "new-shasum", "\"new-etag\"");

        assertEquals("new-shasum",
                Files.readString(Path.of(cacheFile + ".shasum")).strip());
        assertEquals("\"new-etag\"",
                Files.readString(Path.of(cacheFile + ".etag")).strip());
    }

    @Test
    void writeNpmSidecarFilesCreatesScopedPackageDirs(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("@scope/name/-/name-1.0.0.tgz");
        ArtifactCacheHandler.writeNpmSidecarFiles(cacheFile, "abc", "\"etag\"");

        assertTrue(Files.isRegularFile(Path.of(cacheFile + ".shasum")));
    }

    // --- storePackageEtag ---

    @Test
    void storeAndReadPackageEtag(@TempDir Path tmp) throws Exception {
        var origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            ArtifactCacheHandler.storePackageEtag("@openai/codex", "\"W/abc123\"");

            var etagFile = tmp.resolve(".cache/incus-spawn/npm/@openai/codex/.etag");
            assertTrue(Files.isRegularFile(etagFile));
            assertEquals("\"W/abc123\"", Files.readString(etagFile).strip());
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void storePackageEtagOverwritesPrevious(@TempDir Path tmp) throws Exception {
        var origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            ArtifactCacheHandler.storePackageEtag("express", "\"first\"");
            ArtifactCacheHandler.storePackageEtag("express", "\"second\"");

            var etagFile = tmp.resolve(".cache/incus-spawn/npm/express/.etag");
            assertEquals("\"second\"", Files.readString(etagFile).strip());
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    /** Digests of documents with a shasum and no integrity, as stored in the {@code .shasum} sidecar. */
    static final String SHA1_DIGEST = "sha1:" + "a".repeat(40);
    static final String NEW_SHA1_DIGEST = "sha1:" + "b".repeat(40);

    /** Upstream's version document, carrying {@code digest}'s hex as its shasum and no integrity. */
    static SidecarAnswer found(String digest) {
        return document("{\"shasum\":\"" + digest.substring("sha1:".length()) + "\"}");
    }

    /** Upstream's version document with {@code dist} as its dist object. */
    static SidecarAnswer document(String dist) {
        return new SidecarAnswer(200, ("{\"dist\":" + dist + "}").getBytes(StandardCharsets.UTF_8));
    }

    // --- npmDigest: dist.integrity before dist.shasum (#1465) ---

    static final String HELLO_INTEGRITY = "sha512-m3HSJL1i83hdltRq0+o9czGb+8KJDKra4t/3JRlnPKcjI8PZm6XBHXx6zG4UuMXaDEZjR1wuXDre9G9zvN7AQw==";

    static final String HELLO_SHA1_INTEGRITY = "sha1-qvTGHdzF6KLavt4PO0gs2a6pQ00=";

    static String npmDigest(String dist) {
        return ArtifactCacheHandler.npmDigest(document(dist).body());
    }

    /** The dist of a document with {@link #SHA1_DIGEST}'s shasum and {@code integrity}, raw JSON. */
    static String withShasum(String integrity) {
        return "{\"shasum\":\"" + "a".repeat(40) + "\",\"integrity\":" + integrity + "}";
    }

    static String quoted(String text) {
        return "\"" + text + "\"";
    }

    @Test
    void integrityIsPreferredOverTheShasum(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"etag\"", "ref",
                document(withShasum(quoted(HELLO_INTEGRITY))));

        assertEquals(HELLO_SHA512, result.digest(), "the tarball is verified on its SHA-512");
        assertEquals(HELLO_SHA512, Files.readString(Path.of(cacheFile + ".shasum")));
    }

    @Test
    void shasumIsTheFallbackWithoutAnIntegrity() {
        assertEquals(SHA1_DIGEST, npmDigest("{\"shasum\":\"" + "A".repeat(40) + "\"}"));
    }

    @Test
    void integrityMayListSeveralHashes() {
        assertEquals(HELLO_SHA512, npmDigest("{\"integrity\":" + quoted(HELLO_SHA1_INTEGRITY + " sha512-bad " + HELLO_INTEGRITY + "?opt") + "}"));
    }

    @Test
    void unusableStrongIntegrityDoesNotFallBackToTheShasum() {
        for (var integrity : List.of("sha512-not*base64", "sha512-" + "A".repeat(84), "sha512-",
                "sha384-" + "A".repeat(64), "sha256-" + "A".repeat(43) + "=", "sha3-" + "A".repeat(88),
                "SHA512-" + "A".repeat(84), HELLO_SHA1_INTEGRITY + " SHA3-" + "A".repeat(88),
                HELLO_SHA1_INTEGRITY + " sha512-bad", "nodash")) {
            assertNull(npmDigest(withShasum(quoted(integrity))), integrity);
        }
        // Not a string at all
        for (var integrity : List.of("42", "{}", "[" + quoted(HELLO_INTEGRITY) + "]", "true")) {
            assertNull(npmDigest(withShasum(integrity)), integrity);
        }
    }

    @Test
    void integrityAlgorithmIsReadWhateverItsCase() {
        assertEquals(HELLO_SHA512, npmDigest("{\"integrity\":\"SHA512-" + HELLO_INTEGRITY.substring("sha512-".length()) + "\"}"));
        assertEquals("sha1:aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", npmDigest(withShasum(quoted("SHA1-" + HELLO_SHA1_INTEGRITY.substring("sha1-".length())))));
    }

    @Test
    void absentIntegrityFallsBackToTheShasum() {
        for (var integrity : List.of("null", quoted(""), quoted("  "))) {
            assertEquals(SHA1_DIGEST, npmDigest(withShasum(integrity)), integrity);
        }
    }

    @Test
    void sha1IntegrityIsTheOneNpmChecks() {
        var hello = "sha1:aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";
        assertEquals(hello, npmDigest(withShasum(quoted(HELLO_SHA1_INTEGRITY))), "before a shasum that disagrees");
        assertEquals(hello, npmDigest("{\"integrity\":" + quoted(HELLO_SHA1_INTEGRITY) + "}"), "without a shasum");
        assertEquals(SHA1_DIGEST, npmDigest(withShasum(quoted("sha1-bad"))), "an unreadable one leaves the shasum");
    }

    @Test
    void copyStoredUnderABareSha1BeforeIntegrityStillMatchesItsShasum(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedTarball(tmp);
        Files.writeString(Path.of(cacheFile + ".shasum"), "a".repeat(40));

        assertTrue(ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"new\"", "ref", found(SHA1_DIGEST)).cacheHit());
    }

    @Test
    void copyStoredUnderABareSha1IsVerifiedAgainOnTheIntegrity(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedTarball(tmp);
        Files.writeString(Path.of(cacheFile + ".shasum"), "a".repeat(40));

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"new\"", "ref",
                document(withShasum(quoted(HELLO_INTEGRITY))));

        assertFalse(result.cacheHit(), "a SHA-1 match does not stand in for the SHA-512");
        assertEquals(HELLO_SHA512, result.digest());
        assertFalse(Files.exists(cacheFile));
    }

    @Test
    void documentWithNeitherHasNoDigest() {
        assertNull(npmDigest("{}"));
        assertNull(npmDigest("{\"shasum\":\"short\",\"integrity\":42}"));
    }

    // --- npmCacheState: ETag and confirmation age ---

    /** A tarball verified under ETag {@code "abc"}, its shasum last confirmed {@code age} ago. */
    static Path cachedUnderEtag(Path tmp, Duration age) throws IOException {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"abc\"");
        var shasum = Path.of(cacheFile + ".shasum");
        Files.writeString(shasum, "someshasum");
        Files.setLastModifiedTime(shasum, FileTime.from(NOW.minus(age)));
        return cacheFile;
    }

    static final Instant NOW = Instant.now();

    static ArtifactCacheHandler.NpmCacheState state(Path cacheFile, String packageEtag) throws IOException {
        return ArtifactCacheHandler.npmCacheState(cacheFile, packageEtag, ArtifactCacheTiers.DEFAULT, NOW);
    }

    @Test
    void cacheHitEtagMatchServesDirectly(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ofMinutes(5));

        var state = state(cacheFile, "\"abc\"");

        var result = state.hit();
        assertNotNull(result);
        assertTrue(result.cacheHit());
        assertEquals(Files.size(cacheFile), result.size());
        assertNull(result.digest());
        assertFalse(result.confirmAgain(), "within fresh, nothing is asked upstream");
    }

    @Test
    void cacheHitPastFreshIsServedAndConfirmedAgain(@TempDir Path tmp) throws Exception {
        var state = state(cachedUnderEtag(tmp, Duration.ofHours(3)), "\"abc\"");

        assertTrue(state.hit().confirmAgain());
    }

    @Test
    void cacheHitPastMaxStaleIsConfirmedFirstDespiteTheEtag(@TempDir Path tmp) throws Exception {
        // npm ci fetches no packument, so the ETag alone would vouch for the tarball forever (#1466)
        var state = state(cachedUnderEtag(tmp, Duration.ofDays(8)), "\"abc\"");

        assertNull(state.hit());
        assertEquals("\"abc\"", state.packageEtag());
    }

    @Test
    void cacheHitConfirmedInTheFutureIsConfirmedFirst(@TempDir Path tmp) throws Exception {
        assertNull(state(cachedUnderEtag(tmp, Duration.ofHours(-1)), "\"abc\"").hit());
    }

    @Test
    void cacheHitWithoutStoredShasumIsConfirmedFirst(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ZERO);
        Files.delete(Path.of(cacheFile + ".shasum"));

        assertNull(state(cacheFile, "\"abc\"").hit());
    }

    @Test
    void confirmingEveryHitServesNothingUnconfirmed(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ZERO);

        assertNull(ArtifactCacheHandler.npmCacheState(cacheFile, "\"abc\"",
                ArtifactCacheTiers.CONFIRM_EVERY_HIT, NOW).hit());
    }

    @Test
    void matchingShasumRenewsTheConfirmation(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ofDays(8));
        var shasum = Path.of(cacheFile + ".shasum");
        var aged = Files.getLastModifiedTime(shasum);
        Files.writeString(shasum, SHA1_DIGEST);
        Files.setLastModifiedTime(shasum, aged);

        assertTrue(ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"abc\"", "ref", found(SHA1_DIGEST)).cacheHit());

        assertNotNull(ArtifactCacheHandler.npmCacheState(cacheFile, "\"abc\"", ArtifactCacheTiers.DEFAULT,
                Instant.now()).hit(), "fresh again after the match");
    }

    // --- reconcileNpmCopy: the background confirmation's verdict ---

    @Test
    void backgroundConfirmationWritesNothingForATarballAlreadyGone(@TempDir Path tmp) throws Exception {
        // A foreground request evicted it and is fetching it again; its sidecars are not ours to touch
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(Path.of(cacheFile + ".shasum"), NEW_SHA1_DIGEST);
        Files.writeString(Path.of(cacheFile + ".etag"), "\"new\"");

        ArtifactCacheHandler.reconcileNpmCopy(cacheFile, "ref", found(SHA1_DIGEST));

        assertEquals(NEW_SHA1_DIGEST, Files.readString(Path.of(cacheFile + ".shasum")));
        assertEquals("\"new\"", Files.readString(Path.of(cacheFile + ".etag")));
    }

    @Test
    void backgroundConfirmationWaitsForTheArtifactsLock(@TempDir Path tmp) throws Exception {
        // The foreground's evict-and-write holds the same lock, so neither acts on a copy the other replaced
        var cacheFile = cachedUnderEtag(tmp, Duration.ofHours(3));

        assertWaitsForLock(cacheFile, () -> {
            ArtifactCacheHandler.reconcileNpmCopy(cacheFile, "ref", found(NEW_SHA1_DIGEST));
            return null;
        });

        assertFalse(Files.exists(cacheFile));
    }

    @Test
    void foregroundSettleWaitsForTheArtifactsLock(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ofDays(8));

        var result = assertWaitsForLock(cacheFile, () ->
                ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"abc\"", "ref", found(NEW_SHA1_DIGEST)));

        assertFalse(result.cacheHit());
        assertEquals(NEW_SHA1_DIGEST, Files.readString(Path.of(cacheFile + ".shasum")));
    }

    /** Run {@code work} while the test holds the artifact's lock: it must block on it, touching nothing, until released. */
    static <T> T assertWaitsForLock(Path cacheFile, Callable<T> work) throws Exception {
        var task = new FutureTask<>(work);
        var thread = new Thread(task);
        synchronized (VerifiedArtifactStore.lockFor(cacheFile)) {
            thread.start();
            ArtifactCacheProxyTest.await("the work to block on the lock", () -> thread.getState() == Thread.State.BLOCKED);
            assertTrue(Files.exists(cacheFile), "nothing is evicted while the lock is held");
        }
        return task.get(5, TimeUnit.SECONDS);
    }

    @Test
    void backgroundConfirmationMatchRenews(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ofHours(3));
        Files.writeString(Path.of(cacheFile + ".shasum"), SHA1_DIGEST);
        Files.setLastModifiedTime(Path.of(cacheFile + ".shasum"), FileTime.from(NOW.minus(Duration.ofHours(3))));

        ArtifactCacheHandler.reconcileNpmCopy(cacheFile, "ref", found(SHA1_DIGEST));

        assertTrue(Files.getLastModifiedTime(Path.of(cacheFile + ".shasum")).toInstant().isAfter(NOW));
    }

    @Test
    void backgroundConfirmationChangeEvictsAndWritesNoSidecar(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ofHours(3));

        ArtifactCacheHandler.reconcileNpmCopy(cacheFile, "ref", found(NEW_SHA1_DIGEST));

        assertFalse(Files.exists(cacheFile));
        assertFalse(Files.exists(Path.of(cacheFile + ".shasum")));
        assertFalse(Files.exists(Path.of(cacheFile + ".etag")));
    }

    @Test
    void backgroundConfirmationGoneEvicts(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ofHours(3));

        ArtifactCacheHandler.reconcileNpmCopy(cacheFile, "ref", new SidecarAnswer(404, null));

        assertFalse(Files.exists(cacheFile));
        assertFalse(Files.exists(Path.of(cacheFile + ".shasum")));
    }

    @Test
    void backgroundConfirmationInconclusiveExpires(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedUnderEtag(tmp, Duration.ofHours(3));

        ArtifactCacheHandler.reconcileNpmCopy(cacheFile, "ref", new SidecarAnswer(403, null));

        assertTrue(Files.exists(cacheFile), "an answer that confirms nothing evicts nothing");
        assertNull(state(cacheFile, "\"abc\"").hit(), "the next hit is confirmed first");
    }

    @Test
    void cacheHitEmptyEtagFallsToShasumCheck(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "");
        Files.writeString(Path.of(cacheFile + ".shasum"), SHA1_DIGEST);

        assertNull(state(cacheFile, "").hit(), "an empty ETag vouches for nothing");
        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "", "ref",
                found(SHA1_DIGEST));

        assertNotNull(result);
        assertTrue(result.cacheHit());
    }

    // --- checkNpmTarballCache: ETag mismatch, digest unchanged ---

    @Test
    void cacheHitEtagChangedShasumSameUpdatesEtag(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old\"");
        Files.writeString(Path.of(cacheFile + ".shasum"), SHA1_DIGEST);

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"new\"", "ref",
                found(SHA1_DIGEST));

        assertNotNull(result);
        assertTrue(result.cacheHit());
        assertEquals("\"new\"", Files.readString(Path.of(cacheFile + ".etag")).strip(),
                "tarball ETag should be updated to current package ETag");
    }

    @Test
    void cacheHitEtagChangedShasumSamePackageEtagNullSkipsEtagUpdate(@TempDir Path tmp)
            throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old\"");
        Files.writeString(Path.of(cacheFile + ".shasum"), SHA1_DIGEST);

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, null, "ref",
                found(SHA1_DIGEST));

        assertNotNull(result);
        assertTrue(result.cacheHit());
        assertEquals("\"old\"", Files.readString(Path.of(cacheFile + ".etag")).strip(),
                "tarball ETag should NOT be updated when package ETag is null");
    }

    // --- checkNpmTarballCache: ETag mismatch, digest changed (eviction) ---

    @Test
    void cacheHitShasumChangedEvictsAndRefetches(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "old-tarball");
        Files.writeString(Path.of(cacheFile + ".shasum"), "old-shasum");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old-etag\"");

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"new-etag\"", "ref",
                found(NEW_SHA1_DIGEST));

        assertNotNull(result);
        assertFalse(result.cacheHit());
        assertEquals(NEW_SHA1_DIGEST, result.digest());
        assertFalse(Files.exists(cacheFile), "stale tarball should be deleted");
        assertEquals(NEW_SHA1_DIGEST,
                Files.readString(Path.of(cacheFile + ".shasum")).strip(),
                "sidecar should be written with new shasum");
    }

    @Test
    void cacheHitNoStoredShasumTreatsAsMismatch(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old\"");
        // no .shasum file

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"new\"", "ref",
                found(NEW_SHA1_DIGEST));

        assertNotNull(result);
        assertFalse(result.cacheHit(), "missing stored shasum should trigger eviction");
        assertEquals(NEW_SHA1_DIGEST, result.digest());
    }

    // --- checkNpmTarballCache: no digest (#787: only an unreachable registry serves the copy) ---

    static Path cachedTarball(Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".etag"), "\"old\"");
        Files.writeString(Path.of(cacheFile + ".shasum"), SHA1_DIGEST);
        return cacheFile;
    }

    @Test
    void cacheHitRegistryUnreachableServesFromCache(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedTarball(tmp);

        for (var answer : List.of(SidecarAnswer.UNREACHABLE, new SidecarAnswer(503, null), new SidecarAnswer(429, null))) {
            var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"new\"", "ref", answer);

            assertNotNull(result, "status " + answer.status());
            assertTrue(result.cacheHit(), "status " + answer.status());
        }
    }

    @Test
    void cacheHitVersionGoneEvictsAndRelays(@TempDir Path tmp) throws Exception {
        for (var status : List.of(404, 410)) {
            var cacheFile = cachedTarball(tmp);

            assertNull(ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"new\"", "ref",
                    new SidecarAnswer(status, null)));

            assertFalse(Files.exists(cacheFile), "status " + status);
            assertFalse(Files.exists(Path.of(cacheFile + ".shasum")));
            assertFalse(Files.exists(Path.of(cacheFile + ".etag")));
        }
    }

    @Test
    void cacheHitUnverifiableAnswerRelaysWithoutEvicting(@TempDir Path tmp) throws Exception {
        var cacheFile = cachedTarball(tmp);

        for (var answer : List.of(new SidecarAnswer(403, null), SidecarAnswer.UNUSABLE,
                new SidecarAnswer(200, "{not json".getBytes(StandardCharsets.UTF_8)))) {
            assertNull(ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"new\"", "ref", answer),
                    "status " + answer.status());
            assertTrue(Files.exists(cacheFile));
        }
    }

    // --- checkNpmTarballCache: no package ETag ---

    @Test
    void cacheHitNoPackageEtagFallsToShasumVerification(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".shasum"), SHA1_DIGEST);
        // no .etag files at all

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, null, "ref",
                found(SHA1_DIGEST));

        assertNotNull(result);
        assertTrue(result.cacheHit());
    }

    @Test
    void cacheHitBothEtagsNullDoesNotNpe(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "tarball-content");
        Files.writeString(Path.of(cacheFile + ".shasum"), SHA1_DIGEST);

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, null, "ref",
                found(SHA1_DIGEST));

        assertNotNull(result);
        assertTrue(result.cacheHit());
    }

    // --- checkNpmTarballCache: cache miss ---

    @Test
    void cacheMissShasumAvailableReturnsFetchResult(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"etag\"", "ref",
                found(SHA1_DIGEST));

        assertNotNull(result);
        assertFalse(result.cacheHit());
        assertEquals(SHA1_DIGEST, result.digest());
        assertEquals(SHA1_DIGEST,
                Files.readString(Path.of(cacheFile + ".shasum")).strip());
        assertEquals("\"etag\"",
                Files.readString(Path.of(cacheFile + ".etag")).strip());
    }

    @Test
    void cacheMissShasumUnavailableReturnsNull(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, "\"etag\"", "ref",
                SidecarAnswer.UNREACHABLE);

        assertNull(result, "should return null to trigger relay when shasum unavailable");
    }

    @Test
    void cacheMissNoPackageEtagWritesShasumButNotEtag(@TempDir Path tmp) throws Exception {
        var cacheFile = tmp.resolve("pkg/-/pkg-1.0.0.tgz");

        var result = ArtifactCacheHandler.checkNpmTarballCache(cacheFile, null, "ref",
                found(SHA1_DIGEST));

        assertNotNull(result);
        assertFalse(result.cacheHit());
        assertTrue(Files.isRegularFile(Path.of(cacheFile + ".shasum")));
        assertFalse(Files.exists(Path.of(cacheFile + ".etag")),
                "should not write ETag sidecar when package ETag is null");
    }
}
