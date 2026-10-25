package dev.incusspawn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A Homebrew prefix serves every account on the machine, so {@code install.sh} never writes one
 * (#1614), and that holds for an {@code INSTALL_DIR} inside it too (#1666): renaming the build over
 * the prefix's {@code bin/isx} would hand every account's CLI, KeepAlive proxy and Git helper this
 * user's build. The script refuses such a directory, by any spelling, before it builds.
 * <p>
 * The script runs from a fake checkout whose {@code mvnw} and {@code java} are stubs, as are
 * {@code brew} and {@code systemctl}: nothing is built and no Homebrew is needed.
 */
class InstallScriptBrewPrefixTest {

    private static final Path SCRIPT = Path.of("../install.sh").toAbsolutePath();
    private static final Path GIT_REMOTE_HELPER =
            Path.of("../common/src/main/resources/git-remote-isx").toAbsolutePath();
    private static final List<String> BINARIES = List.of("isx", "isx-proxy", "git-remote-isx");
    private static final Path KEG = Path.of("../Cellar/incus-spawn/1.0");

    @TempDir
    Path tmp;

    private Path home;
    private Path checkout;
    private Path stubs;
    private Path brew;
    private Path brewBin;

    record Result(int exit, String output) {}

    @BeforeEach
    void checkoutAndHomebrewInstall() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        stubs = Files.createDirectories(tmp.resolve("stubs"));
        checkout = Files.createDirectories(tmp.resolve("checkout"));
        Files.copy(SCRIPT, checkout.resolve("install.sh"));
        var resources = Files.createDirectories(checkout.resolve("common/src/main/resources"));
        Files.copy(GIT_REMOTE_HELPER, resources.resolve("git-remote-isx"));
        for (var module : List.of("cli", "proxy")) {
            var app = Files.createDirectories(checkout.resolve(module + "/target/quarkus-app"));
            Files.writeString(app.resolve("quarkus-run.jar"), module);
        }
        executable(checkout.resolve("mvnw"), "echo built >> '" + tmp.resolve("built") + "'");

        stub("uname", "echo Linux");
        stub("systemctl", "exit 1");
        stub("java", """
                case "$1" in
                  -version) echo 'openjdk version "25" 2025-09-16' >&2 ;;
                  -XX:*) ;;
                  *) echo "isx 0.0.0" ;;
                esac""");

        brew = tmp.resolve("brew");
        stub("brew", "[ \"$1\" = --prefix ] && echo '" + brew + "'");
        var kegBin = Files.createDirectories(brew.resolve("Cellar/incus-spawn/1.0/bin"));
        for (var binary : BINARIES) executable(kegBin.resolve(binary), "echo homebrew " + binary);
        Files.createSymbolicLink(Files.createDirectories(brew.resolve("opt")).resolve("incus-spawn"), KEG);
        brewBin = Files.createDirectories(brew.resolve("bin"));
        for (var binary : BINARIES) {
            Files.createSymbolicLink(brewBin.resolve(binary), KEG.resolve("bin").resolve(binary));
        }
    }

    @Test
    void refusesThePrefixBin() throws Exception {
        assertRefused(brewBin.toString());
    }

    @Test
    void refusesThePrefixBinWithATrailingSlash() throws Exception {
        assertRefused(brewBin + "/");
    }

    @Test
    void refusesALinkToThePrefixBin() throws Exception {
        var link = Files.createSymbolicLink(home.resolve("brewbin"), brewBin);
        assertRefused(link.toString());
    }

    @Test
    void refusesADotDotSpellingOfThePrefixBin() throws Exception {
        assertRefused(home + "/../brew/bin");
    }

    /** mkdir -p would create "missing", climb back out of it, and create "mine" in the prefix. */
    @Test
    void refusesADotDotThroughAMissingDirectoryWithoutCreatingAnything() throws Exception {
        var result = run(home + "/missing/../../brew/mine");

        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains("name it without '..'"), result.output());
        assertFalse(Files.exists(home.resolve("missing")), result.output());
        assertFalse(Files.exists(brew.resolve("mine")), result.output());
        assertFalse(Files.exists(tmp.resolve("built")), result.output());
    }

    /** brew shellenv exports HOMEBREW_PREFIX: the prefix counts even when brew is not on PATH. */
    @Test
    void refusesThePrefixNamedByHomebrewPrefixWithoutBrewOnPath() throws Exception {
        Files.delete(stubs.resolve("brew"));
        executable(brewBin.resolve("brew"), "exit 1");
        assertRefused(Map.of("HOMEBREW_PREFIX", brew.toString()), brewBin.toString(), BINARIES.size() + 1);
    }

    /** A HOMEBREW_PREFIX with no Homebrew in it is not a prefix, so a plain /usr/local stays usable. */
    @Test
    void ignoresAHomebrewPrefixWithoutBrew() throws Exception {
        Files.delete(stubs.resolve("brew"));
        var plain = Files.createDirectories(tmp.resolve("plain/bin"));

        var result = run(Map.of("HOMEBREW_PREFIX", plain.getParent().toString()), plain.toString());

        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.isRegularFile(plain.resolve("isx"), LinkOption.NOFOLLOW_LINKS), result.output());
    }

    /** brew --prefix may name a link (Linuxbrew's ~/.linuxbrew), and INSTALL_DIR the real directory. */
    @Test
    void refusesThePrefixBinWhenThePrefixIsALink() throws Exception {
        var linked = Files.createSymbolicLink(tmp.resolve("brew-link"), brew);
        stub("brew", "[ \"$1\" = --prefix ] && echo '" + linked + "'");
        assertRefused(brewBin.toString());
    }

    /** The formula's keg is in the prefix as much as its bin is. */
    @Test
    void refusesTheKegBin() throws Exception {
        assertRefused(brew.resolve("opt/incus-spawn/bin").toString());
    }

    /** Refused before mkdir -p, so not even an empty directory lands in the prefix. */
    @Test
    void refusesANewDirectoryInThePrefixWithoutCreatingIt() throws Exception {
        var mine = brew.resolve("mine/bin");
        assertRefused(mine.toString());
        assertFalse(Files.exists(brew.resolve("mine")));
    }

    /** A sibling whose path only starts with the prefix's is not in it. */
    @Test
    void installsBesideThePrefix() throws Exception {
        var sibling = tmp.resolve("brew2/bin");

        var result = run(sibling.toString());

        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.isRegularFile(sibling.resolve("isx"), LinkOption.NOFOLLOW_LINKS),
                result.output());
    }

    @Test
    void installsToTheDefaultDirectory() throws Exception {
        var result = run(null);

        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.isRegularFile(home.resolve(".local/bin/isx"), LinkOption.NOFOLLOW_LINKS),
                result.output());
    }

    /** A broken brew (or one for another architecture) did not abort an install before the check either. */
    @Test
    void installsWhenBrewPrefixFails() throws Exception {
        stub("brew", "exit 1");

        var result = run(null);

        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.isRegularFile(home.resolve(".local/bin/isx"), LinkOption.NOFOLLOW_LINKS),
                result.output());
    }

    /** The error names the directory it could not enter, not an empty path. */
    @Test
    void namesTheDirectoryItCannotResolve() throws Exception {
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root enters a mode-000 directory");
        var locked = Files.createDirectories(tmp.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            var result = run(locked.resolve("bin").toString());

            assertEquals(1, result.exit(), result.output());
            assertTrue(result.output().contains(": " + locked + " is not accessible."), result.output());
            assertFalse(Files.exists(tmp.resolve("built")), result.output());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * A relative INSTALL_DIR is written relative to the caller's directory, so an exported CDPATH
     * must not make the check resolve it elsewhere: here it would judge a harmless {@code safe/bin}
     * while mkdir -p and the install write the prefix's {@code bin}.
     */
    @Test
    void refusesARelativePrefixBinDespiteCdpath() throws Exception {
        var safe = Files.createDirectories(tmp.resolve("safe"));
        Files.createDirectories(safe.resolve("bin"));

        var result = run(Map.of("CDPATH", safe.toString()), "bin", brew);

        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains(", which install.sh never writes: other accounts may use it."), result.output());
        assertFalse(Files.exists(tmp.resolve("built")), result.output());
        assertHomebrewLinks(result, BINARIES.size());
    }

    private void assertRefused(String installDir) throws Exception {
        assertRefused(Map.of(), installDir, BINARIES.size());
    }

    private void assertRefused(Map<String, String> env, String installDir, int brewBinEntries) throws Exception {
        var result = run(env, installDir);

        assertEquals(1, result.exit(), result.output());
        assertTrue(result.output().contains(", which install.sh never writes: other accounts may use it."), result.output());
        assertTrue(result.output().contains("INSTALL_DIR=~/.local/bin"), result.output());
        assertFalse(Files.exists(tmp.resolve("built")), "built before refusing:\n" + result.output());
        assertHomebrewLinks(result, brewBinEntries);
    }

    private void assertHomebrewLinks(Result result, int brewBinEntries) throws IOException {
        for (var binary : BINARIES) {
            var link = brewBin.resolve(binary);
            assertTrue(Files.isSymbolicLink(link), binary + ":\n" + result.output());
            assertEquals(KEG.resolve("bin").resolve(binary), Files.readSymbolicLink(link),
                    binary + ":\n" + result.output());
        }
        try (var entries = Files.list(brewBin)) {
            assertEquals(brewBinEntries, entries.count(), "staged files left in the prefix:\n" + result.output());
        }
        var kegBin = brew.resolve("Cellar/incus-spawn/1.0/bin");
        for (var binary : BINARIES) {
            assertTrue(Files.readString(kegBin.resolve(binary)).contains("echo homebrew " + binary),
                    "keg's " + binary + " replaced:\n" + result.output());
        }
    }

    private void stub(String name, String body) throws IOException {
        executable(stubs.resolve(name), body);
    }

    private static void executable(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Result run(String installDir) throws IOException, InterruptedException {
        return run(Map.of(), installDir);
    }

    private Result run(Map<String, String> env, String installDir) throws IOException, InterruptedException {
        return run(env, installDir, tmp);
    }

    private Result run(Map<String, String> env, String installDir, Path cwd) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("bash", checkout.resolve("install.sh").toString())
                .redirectErrorStream(true).directory(cwd.toFile());
        pb.environment().remove("JAVA_HOME");
        pb.environment().remove("INSTALL_DIR");
        pb.environment().remove("HOMEBREW_PREFIX");
        pb.environment().put("HOME", home.toString());
        if (installDir != null) pb.environment().put("INSTALL_DIR", installDir);
        pb.environment().put("PATH", stubs + ":/usr/bin:/bin");
        pb.environment().putAll(env);
        var process = pb.start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), output);
        return new Result(process.exitValue(), output);
    }
}
