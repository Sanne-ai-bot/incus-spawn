package dev.incusspawn;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The commit {@code isx --version} and {@code isx proxy status} report is the one the build's
 * working tree has checked out, also when that tree is a {@code git worktree} (#1169).
 * <p>
 * Agents and the Mac runner build from worktrees and quote this commit in "Verified on" lines;
 * {@code git-commit-id-maven-plugin} stamped the main clone's HEAD there instead. The test runs
 * each module's stamping plugin, exactly as its pom declares it, in a throwaway pom inside a
 * worktree whose HEAD differs from its main clone's.
 */
class GitCommitStampTest {

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", "../proxy/pom.xml"})
    void stampsTheWorktreeHeadNotTheMainClones(String modulePom) throws Exception {
        var clone = Files.createDirectories(tmp.resolve("clone"));
        git(clone, "init", "-q");
        git(clone, "commit", "-q", "--allow-empty", "-m", "worktree's commit");
        var worktreeHead = git(clone, "rev-parse", "HEAD");
        git(clone, "commit", "-q", "--allow-empty", "-m", "main clone's commit");
        var worktree = tmp.resolve("worktree");
        git(clone, "worktree", "add", "-q", worktree.toString(), worktreeHead);

        Files.writeString(worktree.resolve("pom.xml"), stampingPom(Path.of(modulePom)));
        var mvn = new ArrayList<>(List.of(mvn(), "-o", "-q", "-B", "generate-resources"));
        // Offline, so the nested build must look where the outer one resolved the plugin.
        var localRepository = System.getProperty("isx.test.localRepository");
        if (localRepository != null && !localRepository.isBlank()) mvn.add("-Dmaven.repo.local=" + localRepository);
        run(worktree, mvn.toArray(String[]::new));

        var props = new Properties();
        try (var in = Files.newInputStream(worktree.resolve("target/classes/git.properties"))) {
            props.load(in);
        }
        assertEquals(worktreeHead, props.getProperty("git.commit.id"),
                modulePom + " stamps the main clone's HEAD, not the worktree's");
    }

    /** A minimal pom holding only the plugins of {@code modulePom} that stamp {@code git.commit.id}. */
    private static String stampingPom(Path modulePom) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(modulePom.toFile());
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        var plugins = new StringBuilder();
        var build = (Element) doc.getDocumentElement().getElementsByTagName("build").item(0);
        var nodes = build.getElementsByTagName("plugin");
        for (int i = 0; i < nodes.getLength(); i++) {
            var plugin = (Element) nodes.item(i);
            if (!plugin.getTextContent().contains("git.commit.id")) continue;
            var out = new StringWriter();
            transformer.transform(new DOMSource(plugin), new StreamResult(out));
            plugins.append(out);
        }
        assertFalse(plugins.isEmpty(), modulePom + " declares no plugin stamping git.commit.id");
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>git-commit-stamp</artifactId>
                  <version>1</version>
                  <packaging>jar</packaging>
                  <build><plugins>%s</plugins></build>
                </project>
                """.formatted(plugins);
    }

    private static String mvn() {
        var home = System.getProperty("maven.home");
        if (home != null && !home.isBlank()) {
            var mvn = Path.of(home, "bin", "mvn");
            if (Files.isExecutable(mvn)) return mvn.toString();
        }
        return "mvn";
    }

    private static String git(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-c", "user.name=test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main"));
        cmd.addAll(List.of(args));
        return run(dir, cmd.toArray(String[]::new));
    }

    private static String run(Path dir, String... cmd) throws IOException, InterruptedException {
        var log = Files.createTempFile("git-commit-stamp", ".log");
        try {
            var builder = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            // A hook or `git rebase --exec` exports GIT_DIR and friends: they would point every
            // command here at the real repository instead of the throwaway one.
            builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
            var process = builder.start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail(String.join(" ", cmd) + " timed out:\n" + Files.readString(log));
            }
            var output = Files.readString(log).strip();
            assertEquals(0, process.exitValue(), String.join(" ", cmd) + " failed:\n" + output);
            return output;
        } finally {
            Files.delete(log);
        }
    }
}
