package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DownloadCacheTest {

    @Test
    void cacheFilenamePreservesOriginalName() {
        var filename = DownloadCache.cacheFilename(
                "https://example.com/releases/tool-1.2.3-bin.tar.gz");
        assertTrue(filename.endsWith("-tool-1.2.3-bin.tar.gz"));
        // Should have a hex hash prefix
        assertTrue(filename.matches("^[0-9a-f]+-tool-1\\.2\\.3-bin\\.tar\\.gz$"));
    }

    @Test
    void cacheFilenameDifferentUrlsSameBasename() {
        var a = DownloadCache.cacheFilename("https://a.com/tool.tar.gz");
        var b = DownloadCache.cacheFilename("https://b.com/tool.tar.gz");
        assertNotEquals(a, b, "Different URLs with same basename should produce different cache filenames");
    }

    @Test
    void computeSha256(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello\n");
        var sha256 = DownloadCache.computeSha256(file);
        // sha256 of "hello\n"
        assertEquals("5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03", sha256);
    }

    @Test
    void cacheHitSkipsDownload(@TempDir Path cacheDir) throws IOException {
        var cache = new DownloadCache(cacheDir);

        // Pre-populate the cache with a fake file
        var content = "cached content";
        var fakeFile = cacheDir.resolve(
                DownloadCache.cacheFilename("https://example.com/tool.tar.gz"));
        Files.writeString(fakeFile, content);
        var sha256 = DownloadCache.computeSha256(fakeFile);

        // Should return the cached file without downloading
        var result = cache.download("https://example.com/tool.tar.gz", sha256);
        assertEquals(fakeFile, result);
        assertEquals(content, Files.readString(result));
    }

    @Test
    void sha256MismatchInCacheRedownloads(@TempDir Path cacheDir) throws IOException {
        var cache = new DownloadCache(cacheDir);

        // Pre-populate with wrong content
        var fakeFile = cacheDir.resolve(
                DownloadCache.cacheFilename("https://example.com/tool.tar.gz"));
        Files.writeString(fakeFile, "wrong content");

        // Should attempt to re-download (and fail since there's no real server)
        assertThrows(Exception.class,
                () -> cache.download("https://example.com/tool.tar.gz", "0000000000000000"));
    }

    @Test
    void refusesFileUrls(@TempDir Path tempDir) throws IOException {
        var secret = Files.writeString(tempDir.resolve("id_rsa"), "secret");
        var cache = new DownloadCache(tempDir.resolve("cache"));
        var e = assertThrows(IOException.class, () -> cache.download(secret.toUri().toString(), null));
        assertTrue(e.getMessage().contains("only http:// and https://"), e.getMessage());
    }

    @Test
    void refusesOtherSchemes(@TempDir Path cacheDir) {
        var cache = new DownloadCache(cacheDir);
        for (var url : List.of("ftp://example.com/f", "jar:file:///tmp/x.jar!/a", "FILE:///etc/passwd")) {
            assertThrows(IOException.class, () -> cache.download(url, null), url);
        }
    }

    @Test
    void baseImagesMayUseLocalFiles(@TempDir Path tempDir) throws IOException {
        var image = Files.writeString(tempDir.resolve("image.tar.xz"), "image");
        var cache = new DownloadCache(tempDir.resolve("cache"));
        var listener = new RecordingListener();
        var result = cache.downloadAllowingLocalFile(image.toUri().toString(), DownloadCache.computeSha256(image),
                listener);
        assertEquals("image", Files.readString(result));
        // A fresh copy is reported like a download, so it is not announced as a cache hit.
        assertEquals(List.of("received", "verifying"), listener.events);
        assertEquals(5, listener.lastBytes);
    }

    @Test
    void localFileAllowanceDoesNotLiftTheLoopbackVeto(@TempDir Path cacheDir) {
        var cache = new DownloadCache(cacheDir);
        assertThrows(IOException.class, () -> cache.downloadAllowingLocalFile("http://127.0.0.1:1/x", null));
    }

    @Test
    void hostLocalAddressesAreRecognized() {
        for (var url : List.of("http://localhost/x", "http://127.0.0.1/x", "http://127.8.9.10:8080/x",
                "http://[::1]/x", "http://[::ffff:127.0.0.1]/x", "http://0.0.0.0/x", "http://[::]/x",
                "http://169.254.169.254/latest/meta-data/", "http://[fe80::1]/x")) {
            assertTrue(DownloadCache.resolvesToHostLocal(URI.create(url)), url);
        }
        for (var url : List.of("https://93.184.215.14/x", "https://10.0.0.1/x", "https://[2606:4700::1111]/x")) {
            assertFalse(DownloadCache.resolvesToHostLocal(URI.create(url)), url);
        }
    }

    @Test
    void refusesLoopbackEvenWhenCached(@TempDir Path cacheDir) throws IOException {
        var url = "http://127.0.0.1/tool.tar.gz";
        var cached = Files.writeString(cacheDir.resolve(DownloadCache.cacheFilename(url)), "x");
        var cache = new DownloadCache(cacheDir);
        var e = assertThrows(IOException.class, () -> cache.download(url, DownloadCache.computeSha256(cached)));
        assertTrue(e.getMessage().contains("loopback or link-local"), e.getMessage());
    }

    @Test
    void redirectsAreFollowedAndEachHopIsChecked(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A redirect body longer than the file it points to: must not survive into the download.
        var longBody = "x".repeat(1000).getBytes(StandardCharsets.UTF_8);
        server.createContext("/to-file", ex -> {
            ex.getResponseHeaders().add("Location", "/file");
            ex.sendResponseHeaders(302, longBody.length);
            ex.getResponseBody().write(longBody);
            ex.close();
        });
        server.createContext("/to-secret", ex -> {
            ex.getResponseHeaders().add("Location", "/secret");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        server.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            ex.sendResponseHeaders(301, -1);
            ex.close();
        });
        server.createContext("/file", ex -> {
            var body = "content".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            // The test server is on loopback, so stand in "/secret" for a host-local address.
            var cache = new DownloadCache(cacheDir, uri -> uri.getPath().startsWith("/secret"));

            assertEquals("content", Files.readString(cache.download(base + "/to-file", null)));

            var e = assertThrows(IOException.class, () -> cache.download(base + "/to-secret", null));
            assertTrue(e.getMessage().contains("redirected from " + base + "/to-secret"), e.getMessage());

            e = assertThrows(IOException.class, () -> cache.download(base + "/loop", null));
            assertTrue(e.getMessage().contains("redirects"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static final List<Duration> DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(8));

    /** A cache that records its retry pauses instead of sleeping through them. */
    private static DownloadCache retrying(Path cacheDir, List<Duration> paused) {
        return new DownloadCache(cacheDir, uri -> false, DELAYS, paused::add);
    }

    @Test
    void transientFailuresAreRetriedFromTheOriginalUrl(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirects = new AtomicInteger();
        var fileRequests = new AtomicInteger();
        server.createContext("/release", ex -> {
            redirects.incrementAndGet();
            ex.getResponseHeaders().add("Location", "/asset");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        // Fails transiently, as GitHub release downloads have been seen to, then serves the file.
        server.createContext("/asset", ex -> {
            if (fileRequests.incrementAndGet() <= 2) {
                ex.sendResponseHeaders(500, -1);
            } else {
                var body = "content".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var paused = new ArrayList<Duration>();
            var listener = new RecordingListener();
            var cache = retrying(cacheDir, paused);
            assertEquals("content", Files.readString(cache.downloadAllowingLocalFile(base + "/release", null, listener)));
            assertEquals(3, fileRequests.get());
            assertEquals(3, redirects.get(), "each attempt restarts at the original URL");
            assertEquals(DELAYS, paused);
            assertEquals(List.of("retrying HTTP 500 from 127.0.0.1 2/3", "retrying HTTP 500 from 127.0.0.1 3/3",
                    "received"), listener.events);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void persistentServerErrorsGiveUpNamingEveryFailure(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/broken", ex -> {
            ex.sendResponseHeaders(requests.incrementAndGet() == 1 ? 502 : 503, -1);
            ex.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/broken";
            var e = assertThrows(IOException.class, () -> retrying(cacheDir, new ArrayList<>()).download(url, null));
            assertEquals("Download failed: HTTP 502 from 127.0.0.1; then HTTP 503 from 127.0.0.1 for " + url
                    + " (after 3 attempts)", e.getMessage());
            assertEquals(2, e.getSuppressed().length);
            assertEquals(3, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retryAfterIsHonouredUpToACap(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/busy", ex -> {
            var n = requests.incrementAndGet();
            if (n < 3) {
                ex.getResponseHeaders().add("Retry-After", n == 1 ? "5" : "3600");
                ex.sendResponseHeaders(n == 1 ? 429 : 503, -1);
            } else {
                ex.sendResponseHeaders(200, 2);
                ex.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
            }
            ex.close();
        });
        server.start();
        try {
            var paused = new ArrayList<Duration>();
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/busy";
            assertEquals("ok", Files.readString(retrying(cacheDir, paused).download(url, null)));
            // Longer than the 2s schedule, so honoured; an hour is capped at 30s.
            assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)), paused);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void finalStatusesAreNotRetried(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/missing", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/unsupported", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(501, -1);
            ex.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var paused = new ArrayList<Duration>();
            var cache = retrying(cacheDir, paused);
            var e = assertThrows(IOException.class, () -> cache.download(base + "/missing", null));
            assertEquals("Download failed: HTTP 404 from 127.0.0.1 for " + base + "/missing", e.getMessage());
            assertThrows(IOException.class, () -> cache.download(base + "/unsupported", null));
            assertEquals(2, requests.get());
            assertEquals(List.of(), paused);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aBodyCutShortIsRetried(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var body = "z".repeat(100).getBytes(StandardCharsets.UTF_8);
        server.createContext("/flaky", ex -> {
            ex.sendResponseHeaders(200, body.length);
            // The first response promises 100 bytes and drops the connection after 10.
            ex.getResponseBody().write(body, 0, requests.incrementAndGet() == 1 ? 10 : body.length);
            // Flushed, or the connection closes before the headers and the JDK client retries on its own.
            ex.getResponseBody().flush();
            ex.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/flaky";
            var paused = new ArrayList<Duration>();
            assertEquals(100, Files.size(retrying(cacheDir, paused).download(url, null)));
            assertEquals(2, requests.get());
            assertEquals(1, paused.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theHostLocalCheckRunsAgainBeforeEachAttempt(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/rebind", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/rebind";
            // Stands in for a rebinding domain: public until the first request, host-local after.
            var cache = new DownloadCache(cacheDir, uri -> requests.get() > 0, DELAYS, d -> {});
            var e = assertThrows(IOException.class, () -> cache.download(url, null));
            assertTrue(e.getMessage().contains("loopback or link-local"), e.getMessage());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unresolvableHostsAreNotRetried(@TempDir Path cacheDir) {
        var paused = new ArrayList<Duration>();
        var url = "http://isx-download-test.invalid/tool.tar.gz";
        var e = assertThrows(IOException.class, () -> retrying(cacheDir, paused).download(url, null));
        assertEquals("Download failed: cannot resolve isx-download-test.invalid for " + url, e.getMessage());
        assertEquals(List.of(), paused);
    }

    @Test
    void aFailingListenerIsNotRetriedOrBlamedOnTheHost(@TempDir Path cacheDir) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/file", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(200, 7);
            ex.getResponseBody().write("content".getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            var paused = new ArrayList<Duration>();
            var listener = new DownloadCache.Listener() {
                @Override
                public void received(long bytes, long total) {
                    throw new ArithmeticException("progress bug");
                }
            };
            var e = assertThrows(RuntimeException.class,
                    () -> retrying(cacheDir, paused).downloadAllowingLocalFile(url, null, listener));
            assertEquals("progress bug", e.getMessage());
            assertEquals(1, requests.get());
            assertEquals(List.of(), paused);
        } finally {
            server.stop(0);
        }
    }

    private static final byte[] BODY = "0123456789".repeat(10).getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHANGED = "abcdefghij".repeat(10).getBytes(StandardCharsets.UTF_8);
    private static final Map<String, String> V1 = Map.of("ETag", "\"v1\"");

    /** One server answer per request, numbered from 1. */
    private interface Answer {
        void answer(int request, HttpExchange ex) throws IOException;
    }

    /**
     * Downloads from a server giving {@code answer}, with one retry, recording each request's
     * {@code Range} and {@code If-Range} ({@code -} for none), and returns the file's bytes.
     */
    private static byte[] downloadResuming(Path cacheDir, Answer answer, List<String> requests,
                                           List<Duration> paused, RecordingListener listener) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var count = new AtomicInteger();
        server.createContext("/asset", ex -> {
            var range = ex.getRequestHeaders().getFirst("Range");
            requests.add(range == null ? "-" : range + " " + ex.getRequestHeaders().getFirst("If-Range"));
            answer.answer(count.incrementAndGet(), ex);
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/asset";
            // A single retry: starting over after a refused resume must not use up a second one.
            var cache = new DownloadCache(cacheDir, uri -> false, List.of(Duration.ofSeconds(2)), paused::add);
            return Files.readAllBytes(cache.downloadAllowingLocalFile(url, null, listener));
        } finally {
            server.stop(0);
        }
    }

    /** A 200 with the whole of {@code body} promised, dropping the connection after {@code cutAt} bytes. */
    private static void whole(HttpExchange ex, byte[] body, Map<String, String> headers, int cutAt) throws IOException {
        headers.forEach(ex.getResponseHeaders()::add);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body, 0, Math.min(cutAt, body.length));
        // Flushed, or the connection closes before the headers and the JDK client retries on its own.
        ex.getResponseBody().flush();
        ex.close();
    }

    /** A 206 with {@code body} from {@code from} on, labelled with {@code contentRange}. */
    private static void rest(HttpExchange ex, byte[] body, Map<String, String> headers, int from,
                             String contentRange) throws IOException {
        headers.forEach(ex.getResponseHeaders()::add);
        ex.getResponseHeaders().add("Content-Range", contentRange);
        ex.sendResponseHeaders(206, body.length - from);
        ex.getResponseBody().write(body, from, body.length - from);
        ex.close();
    }

    @Test
    void aBrokenBodyIsResumedFromWhereItStopped(@TempDir Path cacheDir) throws IOException {
        var requests = new ArrayList<String>();
        var paused = new ArrayList<Duration>();
        var listener = new RecordingListener();
        var result = downloadResuming(cacheDir, (n, ex) -> {
            if (n == 1) whole(ex, BODY, V1, 40);
            else rest(ex, BODY, V1, 40, "bytes 40-99/100");
        }, requests, paused, listener);
        assertArrayEquals(BODY, result);
        assertEquals(List.of("-", "bytes=40- \"v1\""), requests, "only the missing range is asked for");
        assertEquals(1, paused.size());
        // The resumed attempt reports where it starts, so its percentage does not jump back to 0.
        assertEquals(List.of(0L, 40L), listener.starts);
    }

    @Test
    void aLastModifiedDateIsEnoughToResume(@TempDir Path cacheDir) throws IOException {
        // Strong only when the server's Date is at least a second later; the JDK server sends today's.
        var dated = Map.of("Last-Modified", "Tue, 01 Oct 2024 10:00:00 GMT");
        var requests = new ArrayList<String>();
        var result = downloadResuming(cacheDir, (n, ex) -> {
            if (n == 1) whole(ex, BODY, dated, 40);
            else rest(ex, BODY, dated, 40, "bytes 40-99/100");
        }, requests, new ArrayList<>(), new RecordingListener());
        assertArrayEquals(BODY, result);
        assertEquals(List.of("-", "bytes=40- Tue, 01 Oct 2024 10:00:00 GMT"), requests);
    }

    @Test
    void aServerIgnoringTheRangeSendsTheWholeBodyAgain(@TempDir Path cacheDir) throws IOException {
        var requests = new ArrayList<String>();
        var result = downloadResuming(cacheDir, (n, ex) -> whole(ex, BODY, V1, n == 1 ? 40 : BODY.length),
                requests, new ArrayList<>(), new RecordingListener());
        assertArrayEquals(BODY, result, "the 200 replaces the partial body instead of being appended to it");
        assertEquals(List.of("-", "bytes=40- \"v1\""), requests);
    }

    @Test
    void aRangeThatDoesNotContinueTheFileStartsOver(@TempDir Path cacheDir) throws IOException {
        var v2 = Map.of("ETag", "\"v2\"");
        // Each answer to the Range request, then the whole file the next request must get.
        record Case(String name, Answer refusal, byte[] file, Map<String, String> headers) {}
        for (var c : List.of(
                // As GitHub's asset store does: a 206 even though If-Range names an older ETag.
                new Case("changed file", (n, ex) -> rest(ex, CHANGED, v2, 40, "bytes 40-99/100"), CHANGED, v2),
                new Case("other start", (n, ex) -> rest(ex, BODY, V1, 30, "bytes 30-99/100"), BODY, V1),
                new Case("416", (n, ex) -> {
                    ex.getResponseHeaders().add("Content-Range", "bytes */100");
                    ex.sendResponseHeaders(416, -1);
                    ex.close();
                }, BODY, V1))) {
            var requests = new ArrayList<String>();
            var paused = new ArrayList<Duration>();
            var result = downloadResuming(cacheDir, (n, ex) -> {
                if (n == 1) whole(ex, BODY, V1, 40);
                else if (n == 2) c.refusal().answer(n, ex);
                else whole(ex, c.file(), c.headers(), c.file().length);
            }, requests, paused, new RecordingListener());
            assertArrayEquals(c.file(), result, c.name() + ": nothing spliced onto the partial body");
            assertEquals(List.of("-", "bytes=40- \"v1\"", "-"), requests, c.name());
            assertEquals(1, paused.size(), c.name() + ": starting over is part of the same attempt");
        }
    }

    @Test
    void withoutAStrongValidatorABrokenBodyStartsOver(@TempDir Path cacheDir) throws IOException {
        // Nothing a 206 could be checked against: a weak ETag, a Last-Modified as recent as the
        // response (the file may change again within that second), or neither.
        var justNow = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
        for (var headers : List.of(Map.of("ETag", "W/\"v1\""), Map.of("Last-Modified", justNow),
                Map.<String, String>of())) {
            var requests = new ArrayList<String>();
            var result = downloadResuming(cacheDir, (n, ex) -> whole(ex, BODY, headers, n == 1 ? 40 : BODY.length),
                    requests, new ArrayList<>(), new RecordingListener());
            assertArrayEquals(BODY, result);
            assertEquals(List.of("-", "-"), requests, headers.toString());
        }
    }

    /** Records what a download reported, in order. */
    private static final class RecordingListener implements DownloadCache.Listener {
        final List<String> events = new ArrayList<>();
        /** The bytes each run of {@link #received} started from. */
        final List<Long> starts = new ArrayList<>();
        long lastBytes = -1;
        long lastTotal;

        @Override
        public void verifyingCached() {
            events.add("verifyingCached");
        }

        @Override
        public void received(long bytes, long total) {
            if (events.isEmpty() || !events.get(events.size() - 1).equals("received")) {
                events.add("received");
                starts.add(bytes);
            }
            lastBytes = bytes;
            lastTotal = total;
        }

        @Override
        public void retrying(String reason, int attempt, int attempts) {
            events.add("retrying " + reason + " " + attempt + "/" + attempts);
        }

        @Override
        public void verifying() {
            events.add("verifying");
        }
    }

    @Test
    void progressReportsBytesAgainstContentLength(@TempDir Path cacheDir) throws IOException {
        var body = "y".repeat(200_000).getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sized", ex -> {
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/chunked", ex -> {
            ex.sendResponseHeaders(200, 0); // no Content-Length
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var cache = new DownloadCache(cacheDir, uri -> false);
            var sha256 = DownloadCache.computeSha256(Files.write(cacheDir.resolveSibling("expected"), body));

            var sized = new RecordingListener();
            cache.downloadAllowingLocalFile(base + "/sized", sha256, sized);
            assertEquals(List.of("received", "verifying"), sized.events);
            assertEquals(body.length, sized.lastBytes);
            assertEquals(body.length, sized.lastTotal);

            var chunked = new RecordingListener();
            cache.downloadAllowingLocalFile(base + "/chunked", null, chunked);
            assertEquals(List.of("received"), chunked.events, "no checksum, so nothing to verify");
            assertEquals(body.length, chunked.lastBytes);
            assertEquals(-1, chunked.lastTotal);

            // The checksum matches the cached copy now: verified, never fetched.
            var hit = new RecordingListener();
            cache.downloadAllowingLocalFile(base + "/sized", sha256, hit);
            assertEquals(List.of("verifyingCached"), hit.events);
        } finally {
            server.stop(0);
        }
    }
}
