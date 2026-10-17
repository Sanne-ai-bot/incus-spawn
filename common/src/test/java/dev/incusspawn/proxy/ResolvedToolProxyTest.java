package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResolvedToolProxyTest {

    @Test
    void basicAuthProducesBase64Header() {
        var auth = new ToolDef.AuthDef();
        auth.setType("basic");
        auth.setUsername("${email}");
        auth.setPassword("${api-token}");

        var proxy = new ResolvedToolProxy("test", "api.example.com", auth,
                Map.of("email", "user@test.com", "api-token", "secret"));

        assertEquals("Authorization", proxy.headerName());
        var expected = "Basic " + Base64.getEncoder().encodeToString("user@test.com:secret".getBytes());
        assertEquals(expected, proxy.computeHeaderValue());
    }

    @Test
    void bearerAuthProducesTokenHeader() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("${api-key}");

        var proxy = new ResolvedToolProxy("test", "api.example.com", auth,
                Map.of("api-key", "my-secret-token"));

        assertEquals("Authorization", proxy.headerName());
        assertEquals("Bearer my-secret-token", proxy.computeHeaderValue());
    }

    @Test
    void headerAuthUsesCustomNameAndTemplate() {
        var auth = new ToolDef.AuthDef();
        auth.setType("header");
        auth.setName("X-Custom-Auth");
        auth.setValue("token-${secret}");

        var proxy = new ResolvedToolProxy("test", "api.example.com", auth,
                Map.of("secret", "abc123"));

        assertEquals("X-Custom-Auth", proxy.headerName());
        assertEquals("token-abc123", proxy.computeHeaderValue());
    }

    @Test
    void bearerWithBlankTokenReturnsNull() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("${api-key}");

        var proxy = new ResolvedToolProxy("test", "api.example.com", auth,
                Map.of("api-key", ""));

        assertNull(proxy.computeHeaderValue());
    }

    @Test
    void basicAuthWithLiteralUsername() {
        var auth = new ToolDef.AuthDef();
        auth.setType("basic");
        auth.setUsername("x-access-token");
        auth.setPassword("${token}");

        var proxy = new ResolvedToolProxy("test", "github.com", auth,
                Map.of("token", "ghp_abc123"));

        var expected = "Basic " + Base64.getEncoder().encodeToString("x-access-token:ghp_abc123".getBytes());
        assertEquals(expected, proxy.computeHeaderValue());
    }

    @Test
    void nullAuthReturnsNull() {
        var proxy = new ResolvedToolProxy("test", "api.example.com", null, Map.of());
        assertNull(proxy.headerName());
        assertNull(proxy.computeHeaderValue());
    }

    @Test
    void matchesPathWithNoPaths() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/anything"));
        assertTrue(proxy.matchesPath("/foo/bar"));
        assertTrue(proxy.matchesPath("/"));
    }

    @Test
    void matchesPathExact() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/mcp"));
        assertTrue(proxy.matchesPath("/mcp/"));
        assertTrue(proxy.matchesPath("/mcp/messages"));
        assertFalse(proxy.matchesPath("/mcpadmin"));
        assertFalse(proxy.matchesPath("/api/build-scan"));
        assertFalse(proxy.matchesPath("/"));
    }

    @Test
    void matchesPathWithQueryString() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/mcp?query=1"));
        assertTrue(proxy.matchesPath("/mcp/sub?query=1"));
        assertFalse(proxy.matchesPath("/api?path=/mcp"));
    }

    @Test
    void matchesPathMultiplePrefixes() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp", "/api/v1"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/mcp"));
        assertTrue(proxy.matchesPath("/api/v1"));
        assertTrue(proxy.matchesPath("/api/v1/data"));
        assertFalse(proxy.matchesPath("/api/v2"));
        assertFalse(proxy.matchesPath("/other"));
    }

    @Test
    void matchesPathNullAuth() {
        var proxy = new ResolvedToolProxy("test", "example.com", null, Map.of());
        assertTrue(proxy.matchesPath("/anything"));
    }

    @Test
    void matchesPathTrailingSlashPrefix() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp/"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/mcp"), "trailing slash normalized: exact match");
        assertTrue(proxy.matchesPath("/mcp/"), "trailing slash normalized: with slash");
        assertTrue(proxy.matchesPath("/mcp/messages"), "trailing slash normalized: subpath");
        assertFalse(proxy.matchesPath("/mcpadmin"), "trailing slash normalized: no boundary");
    }

    @Test
    void matchesPathRootScope() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertTrue(proxy.matchesPath("/"), "root scope matches root");
        assertTrue(proxy.matchesPath("/foo"), "root scope matches any path");
        assertTrue(proxy.matchesPath("/foo/bar"), "root scope matches nested path");
    }

    @Test
    void aScopeNeverMatchesAPathAnUpstreamMayResolveElsewhere() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/mcp", "/api"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        for (var path : List.of("/mcp/../admin", "/mcp/./x", "/mcp/..", "/mcp/x/../../admin",
                "/mcp/%2e%2e/admin", "/mcp/.%2E/admin", "/mcp%2f..%2fadmin", "/mcp/%5c../admin",
                "/mcp\\..\\admin", "/mcp/..;/admin", "/mcp/.;x/y", "/api/%61dmin/users",
                "/api//admin", "/api/;/admin", "/api/%25%32%65", "/mcp/%", "/mcp/%2", "/mcp/%zz",
                "/mcp/%c0%ae%c0%ae/admin", "/mcp/%e0%80%ae/admin", "/mcp/%f0%80%80%ae/admin",
                "/mcp/..%3b/admin", "/mcp/%ff", "/mcp/\u00c0\u00ae\u00c0\u00ae/admin", "/mcp/x/..#",
                "/mcp/..#/a", "/mcp/a b", "/mcp/\"x\"", "/mcp/{x}", "/mcp/x|y", "/mcp/\u00e9",
                "/mcp/..%00/admin", "/mcp/..%09/admin", "/mcp/%7f", "/mcp/..%20/admin", "/mcp/.../admin",
                "/mcp/%20/admin", "/mcp/%EF%BC%8E%EF%BC%8E/admin", "/mcp/%ef%ac%81",
                "/mcp/..%3F/admin", "/mcp/..%23", "/mcp/..+/admin", "/mcp/..%e2%88%95..%e2%88%95admin",
                "/mcp/..%c2%85/admin", "/mcp/..%c2%ad/admin", "/mcp/..%e2%80%8b/admin", "/mcp/..%e2%80%a8/admin",
                "/mcp/..%CC%81/admin", "/mcp/..%CD%8F/admin", "/mcp/..%EF%B8%8F/admin", "/mcp/..%E3%85%A4/admin",
                "/mcp/..%2B/admin", "/mcp/a%CC%B8..%CC%B8admin", "/mcp/a%E2%83%A5b", "/mcp/%EF%AC%81le",
                "/mcp/%E6%97%A5./admin", "/mcp/%CC%81/admin", "/mcp/%E3%85%A4/admin", "/mcp/../admin?x=1")) {
            assertEquals(-1, proxy.pathMatchLength(path), path);
        }
        assertTrue(proxy.matchesPath("/mcp/x?next=/../admin"), "the query is not part of the path");
        assertTrue(proxy.matchesPath("/mcp/my%20file"), "an escape no upstream reads as a path character");
        assertTrue(proxy.matchesPath("/mcp/caf%C3%A9"), "UTF-8 is no other path");
        assertTrue(proxy.matchesPath("/mcp/%E6%97%A5%E6%9C%AC/cafe%CC%81"), "letters and combining marks are literal");
        assertTrue(proxy.matchesPath("/mcp/%E6%97%A5/x%2By"), "a segment of letters alone, '+' beside a letter");
        assertTrue(proxy.matchesPath("/mcp/a:b@c!$&'()*+,=/x;v=1"), "every other pchar is literal");
        assertTrue(proxy.matchesPath("/mcp/a%20b/..x"), "a segment with more than dots and spaces is literal");
        assertTrue(proxy.matchesPath("/mcp/a..b/.c/x."), "dots inside a segment are literal");
        assertTrue(proxy.matchesPath("/mcp/"), "a trailing slash is no empty segment");
    }

    @Test
    void aPathAnUpstreamMayResolveElsewhereStillMatchesEveryPath() {
        var root = new ToolDef.AuthDef();
        root.setType("bearer");
        root.setToken("tok");
        root.setPaths(List.of("/", "/mcp"));
        assertEquals(1, new ResolvedToolProxy("test", "example.com", root, Map.of()).pathMatchLength("/mcp/../admin"),
                "a root scope covers whatever the upstream resolves it to");
    }

    @Test
    void invalidPrefixesMatchNothing() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(java.util.Arrays.asList("", "mcp", "//", null));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertFalse(proxy.matchesPath("/"), "an empty prefix never widens the scope to every path");
        assertFalse(proxy.matchesPath("/mcp"), "a prefix without a leading slash never matches");
        assertEquals(-1, proxy.pathMatchLength("/anything"));

        auth.setPaths(List.of("", "/mcp"));
        assertTrue(proxy.matchesPath("/mcp/x"));
        assertFalse(proxy.matchesPath("/api"), "the valid prefix alone scopes the entry");
    }

    @Test
    void pathMatchLengthPrefersTheLongestPrefix() {
        var auth = new ToolDef.AuthDef();
        auth.setType("bearer");
        auth.setToken("tok");
        auth.setPaths(List.of("/", "/api", "/api/admin/"));
        var proxy = new ResolvedToolProxy("test", "example.com", auth, Map.of());

        assertEquals("/api/admin".length(), proxy.pathMatchLength("/api/admin/users"));
        assertEquals("/api".length(), proxy.pathMatchLength("/api/v1"));
        assertEquals(1, proxy.pathMatchLength("/other"));
        assertEquals(0, new ResolvedToolProxy("test", "example.com", null, Map.of()).pathMatchLength("/x"));
    }
}
