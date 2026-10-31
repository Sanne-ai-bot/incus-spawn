package dev.incusspawn;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The helpers of {@code .github/scripts/test-instance.sh}, sourced without running its checks.
 * Maven Central sometimes answers a CI runner with a 403, which the proxy relays unchanged:
 * the check must say so, with the status and Server header, rather than fail silently (#1810).
 */
class TestInstanceScriptTest {

    private static final Path SCRIPT = Path.of("../.github/scripts/test-instance.sh").toAbsolutePath();

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/refused", exchange -> {
            exchange.getResponseHeaders().add("Server", "AkamaiGHost");
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/pom", exchange -> {
            byte[] body = "<artifactId>junit</artifactId>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void anUpstreamRefusalIsReportedWithItsStatusAndServer() throws Exception {
        var result = fetchMatching("/refused", "<artifactId>junit</artifactId>");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("HTTP 403 (Server: AkamaiGHost)"), result.output);
        assertTrue(result.output.contains("the TLS handshake with the proxy succeeded"), result.output);
    }

    @Test
    void aMatchingBodyPasses() throws Exception {
        var result = fetchMatching("/pom", "<artifactId>junit</artifactId>");
        assertEquals(0, result.exitCode, result.output);
        assertEquals("", result.output);
    }

    @Test
    void aBodyThatDoesNotMatchIsReported() throws Exception {
        var result = fetchMatching("/pom", "^[0-9a-f]{40}$");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("does not match"), result.output);
    }

    @Test
    void aFailedConnectionIsReportedByCurl() throws Exception {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/pom";
        server.stop(0);
        var result = run(url, "<artifactId>junit</artifactId>");
        assertEquals(1, result.exitCode, result.output);
        assertFalse(result.output.contains("handshake"), result.output);
    }

    private record Result(int exitCode, String output) {}

    private Result fetchMatching(String path, String pattern) throws Exception {
        return run("http://127.0.0.1:" + server.getAddress().getPort() + path, pattern);
    }

    // No coreutils timeout(1): macOS does not ship it, and the unit tests run there too.
    private Result run(String url, String pattern) throws Exception {
        Path output = Files.createTempFile("fetch-matching", ".log");
        try {
            var builder = new ProcessBuilder("bash", "--norc", "--noprofile", "-c",
                    "source \"$1\" && fetch_matching \"$2\" \"$3\"", "test", SCRIPT.toString(), url, pattern)
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().keySet().removeIf(name -> name.toLowerCase().endsWith("_proxy"));
            var process = builder.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            process.destroyForcibly();
            String text = Files.readString(output);
            assertTrue(finished, "fetch_matching hung:\n" + text);
            return new Result(process.exitValue(), text);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
