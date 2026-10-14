package dev.incusspawn.config;

import java.time.Duration;
import java.util.Map;

/**
 * {@code git-transfer:} in config.yaml: how long a host-side Git transfer (a host checkout's refresh,
 * a clean snapshot's clone or fetch) may run. Durations are written {@code 30s}, {@code 5m}, {@code 1h};
 * {@code 0} turns that bound off, and a blank or unreadable value means the default.
 *
 * <pre>{@code
 * git-transfer:
 *   stall: 5m      # no output, no I/O and next to no CPU in Git or its helpers for this long
 *   timeout: 3h    # the whole transfer, however busy
 * }</pre>
 *
 * <p>Silence alone is not a stall: resolving deltas or writing a large pack can be quiet for minutes
 * while it works. Read from the raw YAML value, so a mistyped section does not fail config.yaml.
 *
 * @param stall   null when off
 * @param timeout null when off
 */
public record GitTransferConfig(Duration stall, Duration timeout) {
    public static final GitTransferConfig DEFAULT = new GitTransferConfig(Duration.ofMinutes(5), Duration.ofHours(3));

    static GitTransferConfig of(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return DEFAULT;
        return new GitTransferConfig(bound(map.get("stall"), DEFAULT.stall), bound(map.get("timeout"), DEFAULT.timeout));
    }

    private static Duration bound(Object value, Duration fallback) {
        var parsed = value instanceof String || value instanceof Number
                ? ArtifactCacheConfig.parseDuration(String.valueOf(value)) : null;
        if (parsed == null) return fallback;
        return parsed.isZero() ? null : parsed;
    }
}
