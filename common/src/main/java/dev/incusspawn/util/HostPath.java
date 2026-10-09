package dev.incusspawn.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** The host's {@code PATH}, searched in-process rather than by spawning {@code which}. */
public final class HostPath {

    private static final String LINUX_FALLBACK =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private HostPath() {}

    /** {@code path}, or the usual Linux directories when it is unset or blank (as under some service managers). */
    public static String effective(String path) {
        return (path != null && !path.isBlank()) ? path : LINUX_FALLBACK;
    }

    public static boolean isOnPath(String name) {
        for (var dir : effective(System.getenv("PATH")).split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            if (Files.isExecutable(Path.of(dir).resolve(name))) return true;
        }
        return false;
    }
}
