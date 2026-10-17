package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * A fully resolved tool proxy entry ready for the MITM proxy.
 * Contains the domain, auth rule, and actual configuration values
 * (read from config.yaml, not placeholder definitions).
 *
 * @param namespaces the credential namespaces a request must prove before this entry's
 *                   credential is injected ({@link ToolProxyResolver#namespacesOf}); with none it
 *                   never is
 */
public record ResolvedToolProxy(
        String toolName,
        String domain,
        ToolDef.AuthDef auth,
        Map<String, String> configValues,
        Set<String> namespaces
) {

    public ResolvedToolProxy {
        namespaces = Set.copyOf(namespaces);
    }

    /** An entry no proof authorizes, for lookup alone. */
    public ResolvedToolProxy(String toolName, String domain, ToolDef.AuthDef auth, Map<String, String> configValues) {
        this(toolName, domain, auth, configValues, Set.of());
    }

    /**
     * Return the HTTP header name to set.
     * "Authorization" for basic/bearer, or the custom name for header type.
     */
    public String headerName() {
        if (auth == null) return null;
        return "header".equals(auth.getType()) ? auth.getName() : "Authorization";
    }

    /**
     * Whether the given request URI matches this entry's path scope.
     * When no paths are declared, every request matches (backward compatible).
     * When paths are declared, the request path must equal one or start with one followed by '/'
     * ({@link #pathMatchLength}).
     */
    public boolean matchesPath(String requestUri) {
        return pathMatchLength(requestUri) >= 0;
    }

    /**
     * How specific this entry's match of {@code requestUri} is: the length of the longest
     * declared prefix it falls under, 0 when no paths are declared, -1 when none matches. The
     * longest prefix selects among overlapping scopes ({@code /api/admin} over {@code /api}).
     * A path an upstream may serve as another ({@link #mayResolveElsewhere}) matches only the root
     * scope, which covers every path anyway: the target is compared as sent, never normalized
     * (#1453).
     */
    public int pathMatchLength(String requestUri) {
        if (auth == null || auth.getPaths().isEmpty()) return 0;
        var queryIdx = requestUri.indexOf('?');
        int pathEnd = queryIdx >= 0 ? queryIdx : requestUri.length();
        int longest = -1;
        boolean root = false;
        for (var raw : auth.getPaths()) {
            // Without a leading '/' a prefix matches nothing: tool validation rejects it, and an
            // empty one must never widen a scope to every path
            if (raw == null || !raw.startsWith("/")) continue;
            // The root '/' matches every path; a trailing slash is dropped ('/mcp/' is '/mcp')
            if (raw.equals("/")) {
                root = true;
                continue;
            }
            int length = raw.endsWith("/") ? raw.length() - 1 : raw.length();
            boolean matches = length > 1 && pathEnd >= length && requestUri.regionMatches(0, raw, 0, length)
                    && (pathEnd == length || requestUri.charAt(length) == '/');
            if (matches) longest = Math.max(longest, length);
        }
        if (longest > 1 && mayResolveElsewhere(requestUri.substring(0, pathEnd))) longest = -1;
        return root ? Math.max(longest, 1) : longest;
    }

    /**
     * Whether an upstream may serve {@code path} as another path than its literal text: a
     * {@code .} or {@code ..} segment, also before a {@code ;} parameter ({@code ..;}); an empty
     * segment other than a trailing slash, which some servers collapse; a backslash, which some
     * read as {@code /}; or a percent-escape that decodes to one of those, to {@code ;}, to an
     * unreserved character ({@code %2e}, {@code %2f}, {@code %5c}, {@code %3b}, {@code %61}) or to
     * {@code %} (a second decoding), that is malformed, or whose bytes are not strict UTF-8 (an
     * overlong {@code %c0%ae} is a lenient decoder's {@code .}).
     */
    public static boolean mayResolveElsewhere(String path) {
        if (path.indexOf('\\') >= 0) return true;
        boolean nonAscii = false;
        for (int i = path.indexOf('%'); i >= 0; i = path.indexOf('%', i + 3)) {
            int c = escapedByte(path, i);
            if (c < 0) return true;
            if (c >= 0x80) {
                nonAscii = true;
            } else if (Character.isLetterOrDigit(c) || "-._~/\\%;".indexOf(c) >= 0) {
                return true;
            }
        }
        if (nonAscii && !isStrictUtf8(path)) return true;
        var segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            var segment = segments[i];
            int semicolon = segment.indexOf(';');
            var name = semicolon >= 0 ? segment.substring(0, semicolon) : segment;
            if (name.equals(".") || name.equals("..")
                    || name.isEmpty() && (semicolon >= 0 || i < segments.length - 1)) {
                return true;
            }
        }
        return false;
    }

    /** The byte the escape at {@code i} encodes, or -1 when it is not {@code %} and two hex digits. */
    private static int escapedByte(String path, int i) {
        int high = i + 2 < path.length() ? Character.digit(path.charAt(i + 1), 16) : -1;
        int low = high >= 0 ? Character.digit(path.charAt(i + 2), 16) : -1;
        return low < 0 ? -1 : high << 4 | low;
    }

    /** Whether the path, its escapes decoded, is UTF-8 the JDK's strict decoder accepts. */
    private static boolean isStrictUtf8(String path) {
        var bytes = new java.io.ByteArrayOutputStream(path.length());
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '%') {
                bytes.write(escapedByte(path, i));
                i += 2;
            } else {
                bytes.writeBytes(String.valueOf(path.charAt(i)).getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    /**
     * Compute the header value from the auth rule and configuration values.
     * Auth fields use ${configKey} template references resolved against configValues.
     */
    public String computeHeaderValue() {
        if (auth == null) return null;
        return switch (auth.getType()) {
            case "basic" -> {
                var user = substituteRefs(auth.getUsername());
                var pass = substituteRefs(auth.getPassword());
                if (user.isEmpty() && pass.isEmpty()) yield null;
                var encoded = java.util.Base64.getEncoder()
                        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                yield "Basic " + encoded;
            }
            case "bearer" -> {
                var token = substituteRefs(auth.getToken());
                yield token.isEmpty() ? null : "Bearer " + token;
            }
            case "header" -> {
                var result = substituteRefs(auth.getValue());
                yield result.isEmpty() ? null : result;
            }
            default -> null;
        };
    }

    private String substituteRefs(String template) {
        if (template == null) return "";
        var result = template;
        for (var entry : configValues.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
