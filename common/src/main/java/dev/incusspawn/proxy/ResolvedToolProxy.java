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
     * When paths are declared, the request path must equal one or start with one followed by '/'.
     */
    public boolean matchesPath(String requestUri) {
        return pathMatchLength(requestUri) >= 0;
    }

    /**
     * How specific this entry's match of {@code requestUri} is: the length of the longest
     * declared prefix it falls under, 0 when no paths are declared, -1 when none matches. The
     * longest prefix selects among overlapping scopes ({@code /api/admin} over {@code /api}).
     */
    public int pathMatchLength(String requestUri) {
        if (auth == null || auth.getPaths().isEmpty()) return 0;
        var queryIdx = requestUri.indexOf('?');
        int pathEnd = queryIdx >= 0 ? queryIdx : requestUri.length();
        int longest = -1;
        for (var raw : auth.getPaths()) {
            // Without a leading '/' a prefix matches nothing: tool validation rejects it, and an
            // empty one must never widen a scope to every path
            if (raw == null || !raw.startsWith("/")) continue;
            // A trailing slash is dropped ('/mcp/' is '/mcp'); the root '/' matches every path
            int length = raw.length() > 1 && raw.endsWith("/") ? raw.length() - 1 : raw.length();
            boolean matches = raw.equals("/")
                    || length > 1 && pathEnd >= length && requestUri.regionMatches(0, raw, 0, length)
                            && (pathEnd == length || requestUri.charAt(length) == '/');
            if (matches) longest = Math.max(longest, length);
        }
        return longest;
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
