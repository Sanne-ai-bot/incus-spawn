package dev.incusspawn.proxy;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The secret a host route's controller checks before it trusts any identity header (#936): the
 * proxy sends it as {@link #HEADER} on every request it forwards on the route, and nothing else
 * can, since it lives only in an owner-only file on the host,
 * {@code ~/.config/incus-spawn/host-routes/<name>.secret}, which the controller reads too.
 *
 * <p>Never inside an instance: no guest is given it, the proxy strips every client
 * {@code X-Isx-*} header (this one included) on the way in and this one on the way back, and it is
 * never logged. It is one per route, so a controller for one route cannot pass as another's.
 *
 * <p>Created by the proxy the first time it serves the route, and kept: the controller and the
 * proxy read the same file, and deleting it gives the route a new secret at once (the proxy's
 * watcher reloads on it). A file that is not the owner's alone, sits in a directory others can
 * write, is a link, or does not hold a usable secret is refused, and every request to the route
 * with it (503): fail closed, never a route without a secret.
 */
public final class HostRouteSecret {

    /** The header the proxy sends it in. Under {@code X-Isx-}, so a client copy is always stripped. */
    public static final String HEADER = "X-Isx-Route-Secret";
    public static final String DIR = "host-routes";
    public static final String SUFFIX = ".secret";
    private static final String TEMPORARY = ".secret-";
    private static final int MAX_BYTES = 4096;
    /** At least 32 characters (a generated one is 64 hex), and only what a header value carries unchanged. */
    private static final Pattern USABLE = Pattern.compile("[A-Za-z0-9._~+/=-]{32,256}");
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private HostRouteSecret() {}

    /** Where the secret of route {@code name} lives. */
    public static Path file(String name) {
        return file(Environment.configDir(), name);
    }

    static Path file(Path configDir, String name) {
        return configDir.resolve(DIR).resolve(name + SUFFIX);
    }

    /**
     * The secret of route {@code name}, created if there is none yet; null when it cannot be used,
     * said through {@code warn} without the value. <strong>Blocks</strong> on the file system.
     */
    public static String loadOrCreate(String name, Consumer<String> warn) {
        return loadOrCreate(Environment.configDir(), name, warn);
    }

    static String loadOrCreate(Path configDir, String name, Consumer<String> warn) {
        var file = file(configDir, name);
        try {
            var dir = ownerOnlyDirectory(file.getParent());
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) create(dir, file);
            return read(file);
        } catch (Unusable e) {
            warn.accept("Ignoring host route '" + name + "': its secret " + file + " " + e.getMessage());
            return null;
        } catch (IOException | RuntimeException e) {
            warn.accept("Ignoring host route '" + name + "': its secret " + file + " cannot be used: " + e.getMessage());
            return null;
        }
    }

    /** Why a secret file is refused, in words that never include its content. */
    private static final class Unusable extends IOException {
        Unusable(String reason) {
            super(reason);
        }
    }

    /**
     * The directory, made owner-only when it is made here; one that is already there must be a real
     * directory nobody else can write to, or anyone could swap a secret for a link or their own.
     */
    private static Path ownerOnlyDirectory(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir.getParent());
            try {
                if (isPosix(dir.getParent())) {
                    Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(dir);
                }
            } catch (FileAlreadyExistsException raced) {
                // another proxy made it: checked below like any existing one
            }
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new Unusable("is not in a directory: " + dir + " is something else");
        }
        if (isPosix(dir)) {
            var permissions = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new Unusable("is in " + dir + ", which others can write to (chmod 700 it)");
            }
        }
        return dir;
    }

    private static void create(Path dir, Path file) throws IOException {
        removeStaleTemporaries(dir);
        // Written whole under another name, owner-only from the start, then linked into place,
        // which never replaces: a controller never reads a half-written file, and two proxies
        // creating it at once agree on the one that landed first (a rename would replace it)
        var tmp = isPosix(dir)
                ? Files.createTempFile(dir, TEMPORARY, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                : Files.createTempFile(dir, TEMPORARY, ".tmp");
        try {
            Files.writeString(tmp, InstanceSecret.generate() + "\n", StandardCharsets.US_ASCII);
            try {
                Files.createLink(file, tmp);
            } catch (FileAlreadyExistsException raced) {
                // the other one is the secret now
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Temporary files a proxy killed mid-create left behind: after the link, one is a second name
     * for a live secret, which deleting {@code <name>.secret} to rotate it would leave on disk. An
     * hour old at least, so a create under way in another proxy is never cut short.
     */
    private static void removeStaleTemporaries(Path dir) throws IOException {
        var cutoff = System.currentTimeMillis() - 3_600_000L;
        try (var entries = Files.newDirectoryStream(dir, TEMPORARY + "*.tmp")) {
            for (var entry : entries) {
                if (Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis() < cutoff) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static String read(Path file) throws IOException {
        // Judged before it is opened: opening a FIFO blocks until something writes to it, and this
        // runs at startup and inside reload(), which would then never return. Java cannot open
        // with O_NONBLOCK; the directory is the owner's alone, so nobody else can swap the file
        // for one between this check and the open
        if (Files.isSymbolicLink(file)) throw new Unusable("is a link, not a regular file");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Unusable("is not a regular file");
        // Opened without following a link, then judged again by the path's attributes
        String secret;
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Unusable("is not a regular file");
            if (isPosix(file)) {
                var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
                if (!permissions.contains(PosixFilePermission.OWNER_READ)
                        || permissions.stream().anyMatch(p -> !OWNER_ONLY.contains(p))) {
                    throw new Unusable("must be readable by its owner only (chmod 600 it)");
                }
            }
            if (channel.size() > MAX_BYTES) throw new Unusable("is too large to be a secret");
            var bytes = java.nio.ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
                // read it all
            }
            secret = new String(bytes.array(), 0, bytes.position(), StandardCharsets.US_ASCII).strip();
        } catch (java.nio.file.FileSystemLoopException e) {
            throw new Unusable("is a link, not a regular file");
        } catch (IOException e) {
            if (e instanceof Unusable) throw e;
            // ELOOP from O_NOFOLLOW reads as a plain IOException on some platforms
            if (Files.isSymbolicLink(file)) throw new Unusable("is a link, not a regular file");
            throw e;
        }
        if (!USABLE.matcher(secret).matches()) {
            throw new Unusable("must hold 32 to 256 characters of [A-Za-z0-9._~+/=-] (delete it for a new one)");
        }
        return secret;
    }

    private static boolean isPosix(Path file) {
        return file.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

}
