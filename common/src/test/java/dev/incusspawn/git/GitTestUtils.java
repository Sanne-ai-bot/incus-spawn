package dev.incusspawn.git;

import dev.incusspawn.FileTrees;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class GitTestUtils {

    private GitTestUtils() {}

    public static String runGit(Path dir, String... args) throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        // Commits and fetches start auto maintenance (older Git: gc) detached: it could outlive the
        // test and write into the repository while @TempDir deletes it (#1473).
        cmd.addAll(List.of("git", "-c", "gc.auto=0", "-c", "maintenance.auto=false"));
        cmd.addAll(List.of(args));
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            assertEquals(0, exit, "git " + String.join(" ", args) + " failed in " + dir + ": " + output);
            return output;
        } catch (InterruptedException e) {
            try {
                process.destroyForcibly();
            }
            catch (RuntimeException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }

    /** {@link #runGit}'s output, stripped. */
    public static String git(Path dir, String... args) throws IOException, InterruptedException {
        return runGit(dir, args).strip();
    }

    /**
     * A repository that publishes binaries as commits, like a release repository: five commits on
     * {@code main} each replacing {@code isx.deb}, and one more on a {@code stable} branch.
     */
    public static Path releasesFixture(Path parent) throws IOException, InterruptedException {
        var repo = parent.resolve("releases");
        PreparationCache.copyTree(releasesTemplate(), repo, false);
        return repo;
    }

    private static Path releasesTemplateRepo;

    /**
     * The releases repository, built once per JVM and copied for each test: building it takes
     * fifteen Git processes, and a slow macOS runner spends most of a test class on spawning them (#1839).
     */
    private static synchronized Path releasesTemplate() throws IOException, InterruptedException {
        if (releasesTemplateRepo != null) return releasesTemplateRepo;
        var parent = Files.createTempDirectory("isx-releases-fixture");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> FileTrees.deleteQuietly(parent)));
        var repo = parent.resolve("releases");
        runGit(parent, "init", "-q", "-b", "main", repo.toString());
        for (int i = 1; i <= 5; i++) commitBinary(repo, "release " + i);
        runGit(repo, "checkout", "-q", "-b", "stable");
        commitBinary(repo, "stable release");
        runGit(repo, "checkout", "-q", "main");
        releasesTemplateRepo = repo;
        return repo;
    }

    /** Commit a new random {@code isx.deb}, seeded by {@code message}. */
    public static void commitBinary(Path repo, String message) throws IOException, InterruptedException {
        var bytes = new byte[64 * 1024];
        new java.util.Random(message.hashCode()).nextBytes(bytes);
        Files.write(repo.resolve("isx.deb"), bytes);
        runGit(repo, "add", "isx.deb");
        runGit(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", message);
    }

    /** Overwrite {@code repo}'s loose blob {@code id} with zeros of the same length, keeping its name. */
    public static void corruptLooseBlob(Path repo, String id) throws IOException, InterruptedException {
        var loose = repo.resolve(".git/objects").resolve(id.substring(0, 2)).resolve(id.substring(2));
        assertTrue(Files.exists(loose));
        assertTrue(loose.toFile().setWritable(true));
        var wrong = new byte[Integer.parseInt(git(repo, "cat-file", "-s", id))];
        try (var out = new java.util.zip.DeflaterOutputStream(Files.newOutputStream(loose))) {
            out.write(("blob " + wrong.length + "\0").getBytes(StandardCharsets.US_ASCII));
            out.write(wrong);
        }
    }

    /** Every object physically stored in {@code repo}, reachable or not. */
    public static Set<String> allObjects(Path repo) throws IOException, InterruptedException {
        return new HashSet<>(git(repo, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").lines().toList());
    }

    /**
     * Repack {@code gitDir}'s objects so the only stored form of {@code publicId} is a delta against
     * a new private blob that no ref reaches, and return that private blob's ID.
     * Git never writes this layout itself; a copy of the source's packs would leak the private base.
     */
    public static String forcePrivateDeltaBase(Path gitDir, String publicId, byte[] publicBytes) throws Exception {
        var suffix = "PRIVATE BASE SUFFIX".getBytes(StandardCharsets.US_ASCII);
        byte[] secret = Arrays.copyOf(publicBytes, publicBytes.length + suffix.length);
        System.arraycopy(suffix, 0, secret, publicBytes.length, suffix.length);
        var privateFile = gitDir.resolve("private-base");
        Files.write(privateFile, secret);
        var privateId = git(gitDir, "hash-object", "-w", privateFile.toString());
        Files.delete(privateFile);
        var others = allObjects(gitDir);
        others.remove(publicId);
        others.remove(privateId);
        var objects = gitDir.resolve("new-objects");
        var packs = Files.createDirectories(objects.resolve("pack"));
        var input = gitDir.resolve("other-objects");
        Files.write(input, others);
        PreparationGit.run(gitDir, input, null, List.of("pack-objects", packs.resolve("pack").toString()));
        Files.delete(input);
        var bytes = new java.io.ByteArrayOutputStream();
        var stream = new java.io.DataOutputStream(bytes);
        stream.writeBytes("PACK");
        stream.writeInt(2);
        stream.writeInt(2);
        header(bytes, 3, secret.length);
        deflate(bytes, secret);
        int distance = bytes.size() - 12;
        var offset = new ArrayDeque<Integer>();
        offset.addFirst(distance & 127);
        while ((distance >>= 7) != 0) offset.addFirst(128 | (--distance & 127));
        var delta = new java.io.ByteArrayOutputStream();
        varint(delta, secret.length);
        varint(delta, publicBytes.length);
        delta.write(0xf0); // Copy from offset zero, with an explicit three-byte size.
        for (int i = 0; i < 3; i++) delta.write((publicBytes.length >> (i * 8)) & 255);
        header(bytes, 6, delta.size());
        for (int n : offset) bytes.write(n);
        deflate(bytes, delta.toByteArray());
        byte[] checksum = java.security.MessageDigest.getInstance("SHA-1").digest(bytes.toByteArray());
        bytes.write(checksum);
        var pack = packs.resolve("pack-" + HexFormat.of().formatHex(checksum) + ".pack");
        Files.write(pack, bytes.toByteArray());
        git(gitDir, "index-pack", pack.toString());
        assertTrue(git(gitDir, "verify-pack", "-v", pack.toString().replace(".pack", ".idx")).lines()
                .anyMatch(s -> s.startsWith(publicId) && s.endsWith(privateId)), "fixture must force the private delta base");
        PreparationGit.delete(gitDir.resolve("objects"));
        Files.move(objects, gitDir.resolve("objects"));
        return privateId;
    }

    private static void header(java.io.ByteArrayOutputStream out, int kind, int size) {
        int first = (kind << 4) | (size & 15);
        size >>= 4;
        out.write(first | (size == 0 ? 0 : 128));
        while (size != 0) {
            int n = size & 127;
            size >>= 7;
            out.write(n | (size == 0 ? 0 : 128));
        }
    }

    private static void varint(java.io.ByteArrayOutputStream out, int size) {
        while (size >= 128) { out.write((size & 127) | 128); size >>= 7; }
        out.write(size);
    }

    private static void deflate(java.io.ByteArrayOutputStream out, byte[] bytes) throws IOException {
        var compressed = new java.util.zip.DeflaterOutputStream(out);
        compressed.write(bytes);
        compressed.finish();
    }
}
