package dev.incusspawn.git;

import dev.incusspawn.config.GitTransferConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Git in an owned repository. Output goes to files: object enumerations can exceed a pipe or heap. */
final class PreparationGit {
    private static final Set<String> TRANSPORT_ENV = Set.of("GIT_SSH", "GIT_SSH_COMMAND", "GIT_SSH_VARIANT",
            "GIT_ASKPASS", "GIT_SSL_CAINFO", "GIT_SSL_CAPATH", "GIT_SSL_NO_VERIFY", "GIT_PROXY_COMMAND",
            "GIT_HTTP_PROXY_AUTHMETHOD", "GIT_HTTP_USER_AGENT", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM");
    /** Local work on owned repositories: no stall budget, since an fsck or repack can be quiet for long. */
    static final GitTransferConfig LOCAL = new GitTransferConfig(null, Duration.ofMinutes(15));

    private PreparationGit() {}

    /** A transfer {@link GitProcess} stopped for stalling or overrunning: retrying it would only wait again. */
    static final class TransferStopped extends IOException {
        TransferStopped(String message) { super(message); }
    }

    static String git(Path repo, String... args) throws IOException {
        return run(repo, null, null, List.of(args));
    }

    static String run(Path repo, Path input, Path output, List<String> args) throws IOException {
        return run(repo, input, output, args, LOCAL, progress -> {});
    }

    /** {@code progress} sees Git's progress only when {@code args} ask for it ({@code --progress}). */
    static String run(Path repo, Path input, Path output, List<String> args, GitTransferConfig limits,
                      Consumer<GitProcess.Progress> progress) throws IOException {
        var command = new ArrayList<>(List.of("git", "--no-replace-objects", "-c", "gc.auto=0",
                "-c", "maintenance.auto=false", "-c", "core.hooksPath=/dev/null",
                "-c", "pack.writeReverseIndex=false",
                "-c", "fetch.writeCommitGraph=false",
                "-c", "protocol.allow=never", "-c", "protocol.https.allow=always",
                "-c", "protocol.ssh.allow=always", "-c", "protocol.git.allow=always",
                "-c", "protocol.file.allow=never", "-C", repo.toString()));
        command.addAll(args);
        var stdout = output == null ? Files.createTempFile("isx-git-", ".out") : output;
        try {
            var builder = new ProcessBuilder(command);
            // In particular, do not inherit an alternate, a worktree, replace refs or injected -c options.
            // Keep the normal host config/credential helpers for authenticating the real remote.
            builder.environment().keySet().removeIf(k -> k.startsWith("GIT_") && !TRANSPORT_ENV.contains(k));
            // GitProcess makes it non-interactive (prompts, SSH) and kills the whole tree on a stop.
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            builder.environment().put("LC_ALL", "C");
            builder.redirectInput(input == null ? Path.of("/dev/null").toFile() : input.toFile());
            builder.redirectOutput(stdout.toFile());
            var result = GitProcess.run(builder, limits == LOCAL ? null : repo, limits, progress);
            // Never include the command/URL or Git's output: they may contain a credential.
            return switch (result.status()) {
                case OK -> output == null ? Files.readString(stdout).strip() : "";
                case FAILED -> throw new IOException("Git preparation failed: " + verb(args));
                case INTERRUPTED -> throw new IOException("Git preparation interrupted");
                // A slow local step is not a stalled remote: the usual fallbacks still apply.
                case STALLED, TIMED_OUT -> throw limits == LOCAL
                        ? new IOException("Git preparation timed out: " + verb(args)) : new TransferStopped(
                        "Git preparation " + verb(args) + " " + result.stopReason());
            };
        } finally {
            if (output == null) Files.deleteIfExists(stdout);
        }
    }

    /** The Git command {@code args} run, past any {@code -c} options. */
    static String verb(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("-c")) i++;
            else return args.get(i);
        }
        return "git";
    }

    /**
     * SSH without passphrase or host-key prompts, unless the environment or Git's config for {@code repo}
     * ({@code core.sshCommand}) already chooses the SSH command: that choice is the user's, and
     * {@code GIT_SSH_COMMAND} would override it. Such a command can still prompt; the stall budget bounds it.
     * Git's config is read with {@code environment}, the one the real command runs with; when that read
     * cannot tell, the user's possible choice is kept.
     */
    static void nonInteractiveSsh(java.util.Map<String, String> environment, Path repo) {
        if (environment.containsKey("GIT_SSH_COMMAND") || environment.containsKey("GIT_SSH")) return;
        if (repo != null && !sshCommandUnset(environment, repo)) return;
        environment.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
    }

    /** True only when Git says {@code core.sshCommand} is unset ({@code git config --get} exits 1). */
    private static boolean sshCommandUnset(java.util.Map<String, String> environment, Path repo) {
        var probe = new ProcessBuilder("git", "-C", repo.toString(), "config", "--get", "core.sshCommand")
                .redirectInput(Path.of("/dev/null").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        probe.environment().clear();
        probe.environment().putAll(environment);
        Process process = null;
        try {
            process = probe.start();
            return process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 1;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    static String hash(Path file) throws IOException {
        var digest = digest();
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[128 * 1024];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static List<Path> children(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.sorted().toList();
        }
    }

    static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (var p : stream.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
