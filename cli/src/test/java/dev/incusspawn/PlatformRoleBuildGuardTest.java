package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The macOS-role test build (#1800) runs isx and isx-proxy in macOS's role on Linux, where the
 * proxy identifies a caller by its credential proof without the Linux address check. So it must
 * never be what a release ships. It is not a switch in the released code: it is a copy of the
 * uber-jars with another Platform.class, made by {@code scripts/macos-role-build.sh}, which only
 * CI's {@code macos-role} job runs. What keeps it out of a release:
 * <ul>
 *   <li>release.yml checks the uber-jars it uploads with {@code scripts/check-platform-role.sh}:
 *       their Platform.class is the one compiled from common's sources, byte for byte, with no mark
 *       of the test build, and {@code -Dos.name} cannot put them in macOS's role;</li>
 *   <li>a native build fails unless its role is its target OS's ({@code PlatformRoleFeature},
 *       which {@code NativeImageInitializationTest} keeps registered);</li>
 *   <li>and this test: nothing that publishes makes or names the test build, and the job that
 *       makes it uploads nothing but logs.</li>
 * </ul>
 */
class PlatformRoleBuildGuardTest {

    private static final Path ROOT = Path.of("..");
    private static final Path BUILD_SCRIPT = ROOT.resolve("scripts/macos-role-build.sh");
    private static final Path CHECK_SCRIPT = ROOT.resolve("scripts/check-platform-role.sh");
    private static final Path PLATFORM = ROOT.resolve("common/src/main/java/dev/incusspawn/Platform.java");
    private static final Path WORKFLOWS = ROOT.resolve(".github/workflows");
    private static final String MARK = "isx-macos-role-test-build";
    /** What makes or runs the test build. */
    private static final List<String> TEST_BUILD = List.of("macos-role-build.sh", "test-macos-role.sh", "macos-role-test.jar");

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /** The line the build script pins, as it declares it. */
    private static String pinnedLine() throws IOException {
        var m = Pattern.compile("(?m)^LINE='(.*)'$").matcher(read(BUILD_SCRIPT));
        assertTrue(m.find(), BUILD_SCRIPT + " no longer declares the LINE it pins");
        return m.group(1);
    }

    @Test
    void theTestBuildPinsTheOneLineThatDecidesTheRole() throws IOException {
        var line = pinnedLine();
        var text = read(PLATFORM);
        assertEquals(1, text.lines().filter(line::equals).count(),
                "Platform.java must have exactly once the OS_NAME line macos-role-build.sh replaces: " + line);
        assertTrue(line.contains("OS_NAME") && line.contains("/proc/sys/kernel/ostype") && line.contains("SystemVersion.plist"), line);
        // isMacOS and isLinux read only OS_NAME, so pinning it pins the role and nothing else
        assertTrue(text.contains("return OS_NAME.contains(\"mac\");") && text.contains("return OS_NAME.contains(\"linux\");"),
                "Platform's role must come from OS_NAME alone");
    }

    @Test
    void noShippedSourceCarriesTheTestBuildsMark() throws IOException {
        var offenders = new ArrayList<String>();
        for (var module : List.of("common", "cli", "proxy")) {
            try (Stream<Path> files = Files.walk(ROOT.resolve(module).resolve("src/main"))) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    if (read(file).contains(MARK)) offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "the test build's mark belongs only to scripts/macos-role-build.sh");
        assertTrue(read(BUILD_SCRIPT).contains(MARK) && read(CHECK_SCRIPT).contains(MARK),
                "the build script marks its jars and the release check looks for that mark: keep them the same");
    }

    @Test
    void theReleaseChecksTheJarsItUploadsAndNeverMakesTheTestBuild() throws IOException {
        var release = read(WORKFLOWS.resolve("release.yml"));
        for (var name : TEST_BUILD) assertFalse(release.contains(name), "release.yml must not name " + name);
        var steps = JqYaml.parse(release).path("jobs").path("build").path("steps").asList();
        int built = indexOf(steps, s -> s.pathText("name", "").equals("Build uber-jar"));
        int checked = indexOf(steps, s -> s.pathText("run", "").contains("scripts/check-platform-role.sh"));
        int uploaded = indexOf(steps, s -> s.pathText("name", "").equals("Upload uber-jar"));
        assertTrue(built >= 0 && checked > built && uploaded > checked,
                "release.yml must check the uber-jars' platform role after building them and before uploading them");
        var check = steps.get(checked);
        assertEquals(steps.get(built).pathText("if", ""), check.pathText("if", ""), "the check runs wherever the jars are built");
        for (var jar : List.of("dist-jars/incus-spawn-runner.jar", "dist-jars/isx-proxy-runner.jar")) {
            assertTrue(check.pathText("run", "").contains(jar), "the check must cover " + jar);
        }
        // And on every PR, on the same build
        var smoke = JqYaml.parse(read(WORKFLOWS.resolve("test-integration.yml"))).path("jobs").path("uber-jar-smoke");
        assertTrue(smoke.path("steps").asList().stream().anyMatch(s -> s.pathText("run", "").contains("scripts/check-platform-role.sh")),
                "uber-jar-smoke must run scripts/check-platform-role.sh");
    }

    @Test
    void onlyTheMacosRoleJobMakesTheTestBuildAndItUploadsOnlyLogs() throws IOException {
        var makers = new ArrayList<String>();
        try (var files = Files.list(WORKFLOWS)) {
            for (var file : files.filter(f -> f.toString().matches(".*\\.ya?ml")).sorted().toList()) {
                var jobs = JqYaml.parse(read(file)).path("jobs");
                for (var job : jobs.asMap().entrySet()) {
                    var steps = job.getValue().path("steps").asList();
                    if (steps.stream().noneMatch(s -> TEST_BUILD.stream().anyMatch(s.pathText("run", "")::contains))) continue;
                    makers.add(file.getFileName() + " " + job.getKey());
                    for (var step : steps) {
                        if (!step.pathText("uses", "").contains("upload-artifact")) continue;
                        var path = step.path("with").pathText("path", "");
                        assertTrue(path.contains("logs") && !path.contains(".jar"),
                                job.getKey() + " uploads " + path + ": the test build must not leave its job");
                        assertTrue(WorkflowArtifactUploadRetryTest.isDiagnostic(step),
                                job.getKey() + "'s uploads are its logs, a diagnostic: only on always() or failure()");
                    }
                }
            }
        }
        assertEquals(List.of("test-integration.yml macos-role"), makers,
                "only the macos-role job may make or run the macOS-role test build");
    }

    /** A composite action or a script could make it out of sight of the steps above. */
    @Test
    void nothingElseUnderGithubNamesTheTestBuild() throws IOException {
        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(ROOT.resolve(".github"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                if (file.endsWith(Path.of("workflows/test-integration.yml"))) continue;
                var text = read(file);
                if (TEST_BUILD.stream().anyMatch(text::contains)) offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders, "only test-integration.yml's macos-role job may make or run the macOS-role test build");
    }

    @Test
    void noInstallChannelNamesTheTestBuild() throws IOException {
        for (var file : List.of("install.sh", "jbang-catalog.json")) {
            var text = read(ROOT.resolve(file));
            for (var name : TEST_BUILD) assertFalse(text.contains(name), file + " must not name " + name);
        }
    }

    private static int indexOf(List<JqValue> steps, Predicate<JqValue> which) {
        for (int i = 0; i < steps.size(); i++) if (which.test(steps.get(i))) return i;
        return -1;
    }
}
