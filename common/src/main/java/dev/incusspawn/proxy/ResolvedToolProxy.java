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
     * Whether an upstream may serve {@code path} as another path than its literal text:
     * <ul>
     * <li>a raw character outside RFC 3986 {@code pchar} and {@code /}: a backslash some read as
     * {@code /}, a {@code #} some drop with what follows, a control, a space, any byte above
     * ASCII (a raw overlong {@code .});</li>
     * <li>a percent-escape that is malformed, or decodes to an unreserved character, {@code /},
     * {@code \}, {@code ;}, {@code %} (a second decoding), {@code ?}, {@code #}, a control or DEL ({@code %2e},
     * {@code %2f}, {@code %5c}, {@code %3b}, {@code %61}, {@code %25}, {@code %00});</li>
     * <li>escaped bytes that are not strict UTF-8 (the overlong {@code %c0%ae}), or decode to a
     * character other than a letter, digit or mark, or one whose NFKC form holds ASCII (the
     * fullwidth {@code %ef%bc%8e});</li>
     * <li>a segment of only dots and spaces ({@code .}, {@code ..}, {@code ...}, {@code ..%20},
     * {@code ..+}),
     * also before a {@code ;} parameter ({@code ..;}), which servers that trim read as {@code ..};
     * or an empty segment other than a trailing slash, which some servers collapse.</li>
     * </ul>
     */
    public static boolean mayResolveElsewhere(String path) {
        if (path.chars().anyMatch(c -> PATH_CHARS.indexOf(c) < 0)) return true;
        boolean nonAscii = false;
        for (int i = path.indexOf('%'); i >= 0; i = path.indexOf('%', i + 3)) {
            int c = escapedByte(path, i);
            if (c < 0) return true;
            if (c >= 0x80) {
                nonAscii = true;
            } else if (ESCAPABLE.indexOf(c) < 0) {
                return true;
            }
        }
        if (nonAscii && !decodesToItself(path)) return true;
        var segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            var segment = segments[i];
            int semicolon = segment.indexOf(';');
            var name = (semicolon >= 0 ? segment.substring(0, semicolon) : segment).replace("%20", " ").replace('+', ' ');
            if (name.isEmpty()) {
                if (semicolon >= 0 || i < segments.length - 1) return true;
            } else if (name.chars().allMatch(c -> c == '.' || c == ' ')) {
                return true;
            }
        }
        return false;
    }

    /** RFC 3986 {@code pchar} and {@code /}: unreserved, sub-delims, {@code :}, {@code @}, {@code %}. */
    private static final String PATH_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@%/";

    /**
     * The ASCII an escape may decode to: printable characters that are not unreserved, {@code /},
     * {@code \}, {@code ;}, {@code %}, or the {@code ?} and {@code #} a second parse would end the
     * path at. A control, DEL or anything else is refused.
     */
    private static final String ESCAPABLE = " !\"$&'()*+,:<=>@[]^`{|}";

    /** The byte the escape at {@code i} encodes, or -1 when it is not {@code %} and two hex digits. */
    private static int escapedByte(String path, int i) {
        int high = i + 2 < path.length() ? Character.digit(path.charAt(i + 1), 16) : -1;
        int low = high >= 0 ? Character.digit(path.charAt(i + 2), 16) : -1;
        return low < 0 ? -1 : high << 4 | low;
    }

    /**
     * A letter, digit or combining mark: no whitespace, control, format character, symbol a
     * best-fit conversion turns into a separator ({@code U+2215}) or punctuation.
     */
    private static boolean isLetterDigitOrMark(int c) {
        int type = Character.getType(c);
        return Character.isLetterOrDigit(c) || type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    /**
     * Whether the ASCII path, its escapes decoded, is UTF-8 the JDK's strict decoder accepts whose
     * non-ASCII characters are letters, digits or marks with no ASCII in their NFKC form: a
     * normalizing, trimming or best-fit upstream reads none as another.
     */
    private static boolean decodesToItself(String path) {
        var bytes = new java.io.ByteArrayOutputStream(path.length());
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '%') {
                bytes.write(escapedByte(path, i));
                i += 2;
            } else {
                bytes.write(path.charAt(i));
            }
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
        return decoded.codePoints().filter(c -> c >= 0x80).allMatch(c -> isLetterDigitOrMark(c) && java.text.Normalizer
                .normalize(Character.toString(c), java.text.Normalizer.Form.NFKC).chars().allMatch(n -> n >= 0x80));
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
