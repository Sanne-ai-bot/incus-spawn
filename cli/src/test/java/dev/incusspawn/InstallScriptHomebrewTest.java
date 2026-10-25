package dev.incusspawn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

/**
 * A Homebrew prefix is machine state: {@code /opt/homebrew} serves every account on a Mac. So
 * {@code install.sh} never writes it, or another user's KeepAlive proxy, CLI and Git helper run the
 * installer's build out of the installer's home (#1614). It only reverts the links into
 * {@code $INSTALL_DIR} an older install.sh made there, pointing each back into the formula's keg,
 * and says when another isx shadows the build.
 * <p>
 * The script runs from a fake checkout whose {@code mvnw} and {@code java} are stubs, as do
 * {@code brew} and {@code systemctl}: nothing is built and no Homebrew is needed. The prefix is laid
 * out as Homebrew lays it out: a keg in {@code Cellar}, {@code opt/incus-spawn} naming it, and
 * relative links in {@code bin}.
 */
class InstallScriptHomebrewTest {

    private static final Path SCRIPT = Path.of("../install.sh").toAbsolutePath();
    private static final Path GIT_REMOTE_HELPER =
            Path.of("../common/src/main/resources/git-remote-isx").toAbsolutePath();
    private static final List<String> BINARIES = List.of("isx", "isx-proxy", "git-remote-isx");
    private static final String KEG = "../Cellar/incus-spawn/1.0";

    @TempDir
    Path tmp;

    private Path home;
    private Path checkout;
    private Path stubs;
    private Path bin;
    private Path brewBin;
    private Path otherBin;

    record Result(int exit, String output) {}

    @BeforeEach
    void checkoutAndHomebrewInstall() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        bin = Files.createDirectories(home.resolve(".local/bin"));
        stubs = Files.createDirectories(tmp.resolve("stubs"));
        checkout = Files.createDirectories(tmp.resolve("checkout"));
        Files.copy(SCRIPT, checkout.resolve("install.sh"));
        var resources = Files.createDirectories(checkout.resolve("common/src/main/resources"));
        Files.copy(GIT_REMOTE_HELPER, resources.resolve("git-remote-isx"));
        for (var module : List.of("cli", "proxy")) {
            var app = Files.createDirectories(checkout.resolve(module + "/target/quarkus-app"));
            Files.writeString(app.resolve("quarkus-run.jar"), module);
        }
        executable(checkout.resolve("mvnw"), "exit 0");

        stub("uname", "echo Linux");
        stub("systemctl", "exit 1");
        stub("java", """
                case "$1" in
                  -version) echo 'openjdk version "25" 2025-09-16' >&2 ;;
                  -XX:*) ;;
                  *) echo "isx 0.0.0" ;;
                esac""");
        // Anything but --prefix is recorded: the script never needs brew to write the prefix.
        stub("brew", """
                case "$1" in
                  --prefix) echo '%s' ;;
                  *) echo "$*" >> '%s'; exit 1 ;;
                esac""".formatted(tmp.resolve("brew"), tmp.resolve("brew-asked")));

        var brew = tmp.resolve("brew");
        var kegBin = Files.createDirectories(brew.resolve("Cellar/incus-spawn/1.0/bin"));
        for (var binary : BINARIES) executable(kegBin.resolve(binary), "echo homebrew " + binary);
        Files.createSymbolicLink(Files.createDirectories(brew.resolve("opt")).resolve("incus-spawn"), Path.of(KEG));
        // Homebrew's record that the keg is linked.
        Files.createSymbolicLink(Files.createDirectories(brew.resolve("var/homebrew/linked")).resolve("incus-spawn"),
                Path.of("../../../Cellar/incus-spawn/1.0"));
        brewBin = Files.createDirectories(brew.resolve("bin"));
        for (var binary : BINARIES) homebrewLink(binary);

        otherBin = Files.createDirectories(tmp.resolve("other/.local/bin"));
        for (var binary : BINARIES) executable(otherBin.resolve(binary), "echo other " + binary);
    }

    /** As on most Macs, where /opt/homebrew/bin comes first on PATH. */
    @Test
    void leavesTheHomebrewInstallAloneAndSaysItShadowsTheBuild() throws Exception {
        var result = run(brewBin, bin);

        assertEquals(0, result.exit(), result.output());
        assertHomebrewLinks(result);
        assertTrue(result.output().contains("'isx' on your PATH is " + brewBin.resolve("isx") + ", not this build"),
                result.output());
        assertTrue(result.output().contains("brew uninstall incus-spawn"), result.output());
    }

    @Test
    void saysNothingAboutShadowingWhenTheBuildComesFirst() throws Exception {
        var result = run(bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        assertFalse(result.output().contains("not this build"), result.output());
        assertHomebrewLinks(result);
    }

    /**
     * An older install.sh linked the prefix to this user's build: each link goes back into the keg.
     * Not through {@code brew link}, which leaves a keg it counts as linked alone ("Already linked").
     */
    @Test
    void revertsItsOwnEarlierLinks() throws Exception {
        for (var binary : BINARIES) overrideLink(binary, bin);

        var result = run(brewBin, bin);

        assertEquals(0, result.exit(), result.output());
        assertHomebrewLinks(result);
    }

    /** An earlier install to another INSTALL_DIR, still in this user's home, is this user's too. */
    @Test
    void revertsItsOwnLinksFromAnotherInstallDir() throws Exception {
        var earlierBin = Files.createDirectories(home.resolve("bin"));
        for (var binary : BINARIES) {
            executable(earlierBin.resolve(binary), "echo earlier " + binary);
            overrideLink(binary, earlierBin);
        }

        var result = run(bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        assertHomebrewLinks(result);
    }

    /** With the formula gone there is nothing to restore: the override alone goes. */
    @Test
    void removesItsOwnLinksWhenTheFormulaIsGone() throws Exception {
        Files.delete(tmp.resolve("brew/opt/incus-spawn"));
        for (var binary : BINARIES) overrideLink(binary, bin);

        var result = run(bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        for (var binary : BINARIES) {
            assertFalse(Files.exists(brewBin.resolve(binary), LinkOption.NOFOLLOW_LINKS), result.output());
        }
        assertTrue(result.output().contains("Warning: nothing replaces " + brewBin.resolve("isx") + ":"),
                result.output());
        assertFalse(result.output().contains("brew link"), result.output());
    }

    /**
     * An installed but unlinked keg is not linked back by hand: it may be unlinked on purpose. It is
     * also what {@code brew upgrade} leaves when an old override makes its link step fail, so the
     * warning names {@code brew link}.
     */
    @Test
    void doesNotLinkAnUnlinkedKegBack() throws Exception {
        Files.delete(tmp.resolve("brew/var/homebrew/linked/incus-spawn"));
        for (var binary : BINARIES) overrideLink(binary, bin);

        var result = run(bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        for (var binary : BINARIES) {
            assertFalse(Files.exists(brewBin.resolve(binary), LinkOption.NOFOLLOW_LINKS), result.output());
        }
        assertTrue(result.output().contains("Warning: nothing replaces " + brewBin.resolve("isx") + ":"),
                result.output());
        assertTrue(result.output().contains(
                "incus-spawn is installed but not linked; to link it, run: brew link incus-spawn"), result.output());
        assertEquals(1, result.output().split("run: brew link incus-spawn", -1).length - 1, result.output());
        assertTrue(result.output().contains("'brew link --overwrite incus-spawn' replaces those too, another user's included"),
                result.output());
    }

    /** A keg without one of the binaries gets no dangling link for it. */
    @Test
    void restoresOnlyWhatTheKegHas() throws Exception {
        Files.delete(tmp.resolve("brew/Cellar/incus-spawn/1.0/bin/git-remote-isx"));
        for (var binary : BINARIES) overrideLink(binary, bin);

        var result = run(bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        assertEquals(Path.of(KEG, "bin/isx"), Files.readSymbolicLink(brewBin.resolve("isx")), result.output());
        assertFalse(Files.exists(brewBin.resolve("git-remote-isx"), LinkOption.NOFOLLOW_LINKS), result.output());
        assertTrue(result.output().contains("Warning: nothing replaces " + brewBin.resolve("git-remote-isx")),
                result.output());
    }

    /** The tap's incus-spawn-dev installs the same binaries: its keg is restored, and named. */
    @Test
    void restoresTheDevFormulaToo() throws Exception {
        Files.delete(tmp.resolve("brew/opt/incus-spawn"));
        Files.delete(tmp.resolve("brew/var/homebrew/linked/incus-spawn"));
        var devKeg = installDevKeg();
        for (var binary : BINARIES) overrideLink(binary, bin);

        var result = run(brewBin, bin);

        assertEquals(0, result.exit(), result.output());
        assertLinksInto(devKeg, result);
        assertTrue(result.output().contains("brew uninstall incus-spawn-dev"), result.output());
    }

    /**
     * The two formulae conflict, so both may be installed with incus-spawn unlinked
     * ({@code brew unlink incus-spawn && brew install incus-spawn-dev}): the linked dev keg is the one
     * restored, not none at all.
     */
    @Test
    void restoresTheLinkedKegBesideAnUnlinkedOne() throws Exception {
        Files.delete(tmp.resolve("brew/var/homebrew/linked/incus-spawn"));
        var devKeg = installDevKeg();
        for (var binary : BINARIES) overrideLink(binary, bin);

        var result = run(brewBin, bin);

        assertEquals(0, result.exit(), result.output());
        assertLinksInto(devKeg, result);
        assertFalse(result.output().contains("nothing replaces"), result.output());
        assertTrue(result.output().contains("brew uninstall incus-spawn-dev"), result.output());
    }

    /** A second, linked keg {@code incus-spawn-dev}, holding the same binaries. */
    private Path installDevKeg() throws IOException {
        var brew = tmp.resolve("brew");
        var kegBin = Files.createDirectories(brew.resolve("Cellar/incus-spawn-dev/1.0/bin"));
        for (var binary : BINARIES) executable(kegBin.resolve(binary), "echo homebrew-dev " + binary);
        var devKeg = Path.of("../Cellar/incus-spawn-dev/1.0");
        Files.createSymbolicLink(brew.resolve("opt/incus-spawn-dev"), devKeg);
        Files.createSymbolicLink(brew.resolve("var/homebrew/linked/incus-spawn-dev"),
                Path.of("../../../Cellar/incus-spawn-dev/1.0"));
        return devKeg;
    }

    private void assertLinksInto(Path keg, Result result) throws IOException {
        for (var binary : BINARIES) {
            assertEquals(keg.resolve("bin").resolve(binary), Files.readSymbolicLink(brewBin.resolve(binary)),
                    result.output());
        }
    }

    /** Uninstalling the formula would not remove another user's link that shadows the build. */
    @Test
    void doesNotSuggestUninstallingTheFormulaForAForeignLink() throws Exception {
        for (var binary : BINARIES) overrideLink(binary, otherBin);

        var result = run(brewBin, bin);

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("not this build"), result.output());
        assertFalse(result.output().contains("brew uninstall"), result.output());
    }

    /** Without the relink, an INSTALL_DIR missing from PATH leaves Homebrew's isx running: say so. */
    @Test
    void saysWhichIsxRunsWhenTheBuildIsNotOnPath() throws Exception {
        var result = run(brewBin, stubs);

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains(bin + " is not on your PATH"), result.output());
        assertTrue(result.output().contains("'isx' on your PATH is " + brewBin.resolve("isx") + ", not this build"),
                result.output());
    }

    /** Links into another user's home were not this user's to make, nor to undo. */
    @Test
    void leavesLinksIntoAnotherUsersHomeAloneAndNamesThem() throws Exception {
        for (var binary : BINARIES) overrideLink(binary, otherBin);

        var result = run(bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        assertOtherUsersLinks(result);
        assertTrue(result.output().contains(
                "Warning: " + brewBin.resolve("isx-proxy") + " links to " + otherBin.resolve("isx-proxy")),
                result.output());
    }

    /** With no linked keg, linking one is not the advice: it was unlinked on purpose, or is not there. */
    @Test
    void adviceForAForeignLinkNamesNoUnlinkedFormula() throws Exception {
        Files.delete(tmp.resolve("brew/var/homebrew/linked/incus-spawn"));
        overrideLink("isx-proxy", otherBin);

        var result = run(bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Whoever made it can remove the link.\n"), result.output());
        assertFalse(result.output().contains("brew link"), result.output());
    }

    /**
     * An empty HOME (env -i, cron, launchd) or "/" names nobody's home: "$HOME"/* would match every
     * absolute path, and another user's link would be removed from the shared prefix.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "/"})
    void anEmptyHomeMakesNoLinkItsOwn(String emptyHome) throws Exception {
        for (var binary : BINARIES) overrideLink(binary, otherBin);

        var result = run(Map.of("HOME", emptyHome), bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        assertOtherUsersLinks(result);
    }

    /** Reverting its own link leaves another user's link beside it in place. */
    @Test
    void revertsOnlyItsOwnLinkBesideAnotherUsers() throws Exception {
        overrideLink("isx", bin);
        overrideLink("isx-proxy", otherBin);

        var result = run(bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        assertEquals(Path.of(KEG, "bin/isx"), Files.readSymbolicLink(brewBin.resolve("isx")), result.output());
        assertEquals(otherBin.resolve("isx-proxy"), Files.readSymbolicLink(brewBin.resolve("isx-proxy")),
                result.output());
        assertFalse(Files.exists(tmp.resolve("brew-asked")), result.output());
    }

    /** INSTALL_DIR spelled with a trailing slash is still the isx PATH finds. */
    @Test
    void aTrailingSlashIsNotAnotherIsx() throws Exception {
        var result = run(Map.of("INSTALL_DIR", bin + "/"), bin, brewBin);

        assertEquals(0, result.exit(), result.output());
        assertFalse(result.output().contains("not this build"), result.output());
        assertFalse(result.output().contains("not on your PATH"), result.output());
    }

    private void homebrewLink(String binary) throws IOException {
        Files.deleteIfExists(brewBin.resolve(binary));
        Files.createSymbolicLink(brewBin.resolve(binary), Path.of(KEG, "bin", binary));
    }

    /** What an install.sh before #1614 left: the prefix's link replaced by one into {@code dir}. */
    private void overrideLink(String binary, Path dir) throws IOException {
        Files.delete(brewBin.resolve(binary));
        Files.createSymbolicLink(brewBin.resolve(binary), dir.resolve(binary));
    }

    private void assertHomebrewLinks(Result result) throws IOException {
        for (var binary : BINARIES) {
            assertEquals(Path.of(KEG, "bin", binary), Files.readSymbolicLink(brewBin.resolve(binary)),
                    binary + ":\n" + result.output());
        }
        assertFalse(Files.exists(tmp.resolve("brew-asked")), result.output());
    }

    private void assertOtherUsersLinks(Result result) throws IOException {
        for (var binary : BINARIES) {
            assertEquals(otherBin.resolve(binary), Files.readSymbolicLink(brewBin.resolve(binary)), result.output());
        }
        assertFalse(Files.exists(tmp.resolve("brew-asked")), result.output());
    }

    private void stub(String name, String body) throws IOException {
        executable(stubs.resolve(name), body);
    }

    private static void executable(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** Runs install.sh with {@code first} ahead of {@code second} on PATH, after the stubs. */
    private Result run(Path first, Path second) throws IOException, InterruptedException {
        return run(Map.of(), first, second);
    }

    private Result run(Map<String, String> env, Path first, Path second) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("bash", checkout.resolve("install.sh").toString()).redirectErrorStream(true);
        pb.environment().remove("JAVA_HOME");
        pb.environment().put("HOME", home.toString());
        pb.environment().put("INSTALL_DIR", bin.toString());
        pb.environment().put("PATH", stubs + ":" + first + ":" + second + ":/usr/bin:/bin");
        pb.environment().putAll(env);
        var process = pb.start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), output);
        return new Result(process.exitValue(), output);
    }
}
