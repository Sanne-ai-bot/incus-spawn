package dev.incusspawn;

import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * On Linux, {@code install.sh --native} builds inside a cached builder image FROM Oracle's
 * {@code native-image} image. That image's {@code :latest} is the 25.0 LTS line, not the innovation
 * line the release builds with, so local builds once ran a different GraalVM from CI and releases.
 * The base must be the floating tag of the Linux {@code graalvm-version} in release.yml
 * ({@code 25.4} is {@code 25i4}), which also names install.sh's cached builder, and the CLI's
 * and the proxy's default builder image must be the same: a bump that reaches the workflows but not these leaves
 * local builds on the old GraalVM, silently, as the 25.3 -> 25.4 upgrade did.
 */
class InstallScriptGraalVmTest {

    private static final Path INSTALL_SCRIPT = Path.of("../install.sh");
    private static final Path RELEASE_WORKFLOW = Path.of("../.github/workflows/release.yml");
    private static final List<Path> BUILDER_DEFAULTS = List.of(
            Path.of("src/main/resources-filtered/application.properties"),
            Path.of("../proxy/src/main/resources-filtered/application.properties"));

    private static final String NATIVE_IMAGE = "container-registry.oracle.com/graalvm/native-image";
    /** Oracle Linux 10, as {@code :latest} is: the bare {@code 25i4} tag is amd64 only, this one multi-arch. */
    private static final String OS = "-ol10";

    private static final Pattern GRAALVM_BASE =
            Pattern.compile("^\\s*GRAALVM_BASE=\"([^\"]+)\"", Pattern.MULTILINE);

    @Test
    void builderImagesFollowTheReleasedLinuxGraalVm() throws IOException {
        var versions = linuxReleaseGraalVmVersions();
        assertEquals(1, versions.size(), "release.yml must build every Linux binary with one graalvm-version");
        var image = NATIVE_IMAGE + ":" + imageTag(versions.iterator().next()) + OS;

        var matcher = GRAALVM_BASE.matcher(Files.readString(INSTALL_SCRIPT));
        assertTrue(matcher.find(), "install.sh must define GRAALVM_BASE");
        assertEquals(image, matcher.group(1),
                "install.sh must build FROM the image of the Linux graalvm-version in release.yml");

        for (var path : BUILDER_DEFAULTS) {
            var properties = new Properties();
            try (var in = Files.newBufferedReader(path)) {
                properties.load(in);
            }
            assertEquals(image, properties.getProperty("quarkus.native.builder-image"),
                    path + ": the default builder image must be the released Linux GraalVM");
        }
    }

    @Test
    void innovationReleasesMapToTheirFloatingTag() {
        assertEquals("25i4", imageTag("25.4"));
        assertEquals("25i12", imageTag("25.12"));
        assertEquals("25", imageTag("25.0"));
    }

    /** setup-graalvm's {@code X.Y} as Oracle's floating image tag: {@code XiY}, or {@code X} for the LTS. */
    static String imageTag(String graalVmVersion) {
        var parts = graalVmVersion.split("\\.");
        assertEquals(2, parts.length, "graalvm-version must be X.Y, was " + graalVmVersion);
        return "0".equals(parts[1]) ? parts[0] : parts[0] + "i" + parts[1];
    }

    private static Set<String> linuxReleaseGraalVmVersions() throws IOException {
        var include = JqYaml.parse(Files.readString(RELEASE_WORKFLOW))
                .path("jobs").path("build").path("strategy").path("matrix").path("include");
        var versions = new HashSet<String>();
        for (var entry : include.arrayValue()) {
            if ("linux".equals(entry.path("os").asText())) {
                versions.add(entry.path("graalvm-version").asText());
            }
        }
        assertFalse(versions.isEmpty(), "release.yml must build Linux binaries with a graalvm-version");
        return versions;
    }
}
