package dev.incusspawn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@code appliance/kernel/build-kernel.sh} up to extraction, with stub {@code curl} and
 * {@code tar} that record their calls; the stub {@code tar} fails, so the script never builds.
 *
 * <p>Pins #1128: the appliance kernel source is downloaded only from kernel.org and must match the
 * pinned {@code KERNEL_SHA256} before it is extracted, a tarball restored from CI's cache included.
 */
@EnabledOnOs(value = OS.LINUX, architectures = {"amd64", "x86_64", "aarch64"})
class KernelSourceVerificationTest {

    private static final Path SCRIPT = Path.of("../appliance/kernel/build-kernel.sh").toAbsolutePath();

    /** Appends the stub's name and arguments to the call log. */
    private static final String RECORD = "echo \"$(basename \"$0\") $*\" >> \"$CALLS\"\n";

    @TempDir
    Path dir;

    private Path bin;
    private Path cache;
    private Path calls;
    private String version;

    @BeforeEach
    void setUp() throws IOException {
        bin = Files.createDirectory(dir.resolve("bin"));
        cache = Files.createDirectory(dir.resolve("cache"));
        calls = Files.createFile(dir.resolve("calls.log"));
        version = pinned("KERNEL_VERSION");
        stub("tar", RECORD + "exit 1\n");
    }

    @Test
    void cachedTarballThatDoesNotMatchIsRejectedBeforeExtraction() throws Exception {
        var tarball = cache.resolve("linux-" + version + ".tar.xz");
        Files.writeString(tarball, "poisoned cache");
        stub("curl", RECORD + "exit 1\n");

        var out = run();

        assertFalse(out.contains("tar "), "an unverified cached tarball was extracted:\n" + out);
        assertTrue(out.contains("checksum mismatch"), out);
        assertFalse(Files.exists(tarball), "a tarball that failed verification must not stay in the cache");
    }

    @Test
    void downloadThatDoesNotMatchIsRejectedBeforeExtraction() throws Exception {
        // Records the call, then writes a tampered body to the -o file.
        stub("curl", RECORD + """
                while [ $# -gt 0 ]; do [ "$1" = -o ] && { echo tampered > "$2"; exit 0; }; shift; done
                exit 1
                """);

        var out = run();

        assertTrue(out.contains("curl ") && out.contains("https://cdn.kernel.org/"), out);
        assertFalse(out.contains("tar "), "an unverified download was extracted:\n" + out);
        assertTrue(out.contains("checksum mismatch"), out);
        try (var left = Files.list(cache)) {
            assertEquals(0, left.count(), "a download that failed verification must not stay in the cache");
        }
    }

    @Test
    void failedKernelOrgDownloadHasNoFallback() throws Exception {
        stub("curl", RECORD + "exit 22\n");

        var out = run();

        assertTrue(out.contains("https://cdn.kernel.org/"), out);
        assertFalse(out.contains("github.com"), "the source must come only from kernel.org:\n" + out);
        // A fallback through another tool would not reach the stub curl.
        assertEquals(1, Files.readAllLines(SCRIPT).stream()
                        .filter(l -> !l.strip().startsWith("#") && l.contains("://")).count(),
                "build-kernel.sh must name exactly one download URL");
        assertFalse(out.contains("tar "), out);
    }

    @Test
    void matchingTarballIsExtracted() throws Exception {
        Files.writeString(cache.resolve("linux-" + version + ".tar.xz"), "stands for the real tarball");
        stub("curl", RECORD + "exit 1\n");
        // The real hash of the real tarball cannot be produced here; this pins only that a match proceeds.
        stub("sha256sum", "echo \"" + pinned("KERNEL_SHA256") + "  $1\"\n");

        var out = run();

        assertFalse(out.contains("checksum mismatch"), out);
        assertFalse(out.contains("curl "), "a cached tarball must not be downloaded again:\n" + out);
        assertTrue(out.contains("tar xf " + cache.resolve("linux-" + version + ".tar.xz")), out);
    }

    /** @return the recorded calls, then the script's output; the script must have failed */
    private String run() throws IOException, InterruptedException {
        var output = dir.resolve("output.log");
        var pb = new ProcessBuilder("/bin/bash", SCRIPT.toString(), dir.resolve("out").toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("CALLS", calls.toString());
        pb.environment().put("KERNEL_CACHE_DIR", cache.toString());
        var process = pb.start();
        var finished = process.waitFor(30, TimeUnit.SECONDS);
        process.destroyForcibly();
        var log = Files.readString(calls) + "--- script output ---\n" + Files.readString(output);
        assertTrue(finished, "the script hung:\n" + log);
        assertNotEquals(0, process.exitValue(), "the stub tar fails, so the script cannot succeed:\n" + log);
        return log;
    }

    private static String pinned(String name) throws IOException {
        var m = Pattern.compile("(?m)^" + name + "=\"([^\"]+)\"").matcher(Files.readString(SCRIPT));
        assertTrue(m.find(), name + " is not set in " + SCRIPT);
        return m.group(1);
    }

    private void stub(String name, String body) throws IOException {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
