package dev.incusspawn.incus;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;

/**
 * A handle to a specific Incus container, providing a convenient API
 * for running commands and managing files inside it.
 */
public class Container {

    private final IncusClient incus;
    private final String name;
    private final Output out;
    private String architecture;
    private MachineType machineType;

    /** A container whose failed commands' output goes to whoever owns the terminal. */
    public Container(IncusClient incus, String name) {
        this(incus, name, BuildOutput.bound());
    }

    /** A container whose failed commands' output is reported to {@code out}. */
    public Container(IncusClient incus, String name, Output out) {
        this.incus = incus;
        this.name = name;
        this.out = out;
    }

    /** Where this container reports what a failed command printed. */
    public Output out() {
        return out;
    }

    /**
     * The failure of a command that printed {@code result}: a warning to {@code out} with the end
     * of what it printed, shown as text, since a tool's run step or an install script decides that
     * (#1588), and the exception to throw, carrying all of it for the build's failure report, which
     * keeps what the warning's {@link BuildOutput#FAILED_OUTPUT_LINES} leave out.
     */
    public static IncusException failed(Output out, String title, String message, IncusClient.ExecResult result) {
        var lines = BuildOutput.guestLines(result.stdout(), result.stderr());
        var failure = new IncusException(message);
        if (lines.length == 0) return failure;
        out.warn(title, BuildOutput.lastOf(lines));
        // Under its title, and set apart from the diagnostics the failure report adds after it
        return failure.withStepOutput(title + "\n" + String.join("\n", lines) + "\n\n");
    }

    public String name() {
        return name;
    }

    /** The Incus machine type (container or VM). Cached after first lookup. */
    public MachineType machineType() {
        if (machineType == null) {
            machineType = incus.machineType(name);
        }
        return machineType;
    }

    public void waitForPath(String path) {
        for (int i = 0; i < 30; i++) {
            if (exec("test", "-e", path).success()) return;
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
        throw new IncusException("Timed out waiting for " + path + " in " + name);
    }

    public void addDiskDevice(String deviceName, String hostSource, String containerPath, boolean readonly) {
        var args = new java.util.ArrayList<>(java.util.List.of(
                "source=" + hostSource,
                "path=" + containerPath));
        if (readonly) args.add("readonly=true");
        incus.deviceAdd(name, deviceName, "disk", args.toArray(String[]::new));
    }

    public void removeDiskDevice(String deviceName) {
        incus.deviceRemove(name, deviceName);
    }

    /** Run a command as root. Returns the result for inspection. */
    public IncusClient.ExecResult exec(String... command) {
        return incus.shellExec(name, command);
    }

    /** Run a shell snippet (sh -c) as root. */
    public IncusClient.ExecResult sh(String script) {
        return incus.shellExec(name, "sh", "-c", script);
    }

    /** Run a command as root with inherited IO (visible output). Fails on non-zero exit. */
    public void runInteractive(String failureMessage, String... command) {
        int exitCode = incus.shellExecInteractive(name, command);
        if (exitCode != 0) {
            throw new IncusException(failureMessage + " (exit code " + exitCode + ")");
        }
    }

    /** Run a command as root, suppressing output. On failure, reports the captured output before throwing. */
    public void runQuiet(String failureMessage, String... command) {
        var result = incus.shellExec(name, command);
        if (!result.success()) {
            throw failed(out, "The failed command printed:", failureMessage + " (exit code " + result.exitCode() + ")", result);
        }
    }

    /**
     * Run a command as root, streaming each output line (stdout and stderr) to
     * {@code lineSink} instead of the terminal. Returns the exit code. The sink is
     * invoked from the exec reader thread(s), so it must be thread-safe.
     */
    public int execLines(java.util.function.Consumer<String> lineSink, String... command) {
        try (var out = new dev.incusspawn.util.LineOutputStream(lineSink);
             var err = new dev.incusspawn.util.LineOutputStream(lineSink)) {
            return incus.shellExecStreaming(name, out, err, command);
        }
    }

    /** Run a shell snippet as a specific user with a login shell. Returns the result for inspection. */
    public IncusClient.ExecResult shAsUser(String user, String script) {
        return incus.execInContainer(name, user, script);
    }

    /** Run a shell snippet as a specific user with a login shell. Fails on non-zero exit. */
    public void runAsUser(String user, String script, String failureMessage) {
        int exitCode = incus.shellExecInteractiveAsUser(name, user, script);
        if (exitCode != 0) {
            throw new IncusException(failureMessage + " (exit code " + exitCode + ")");
        }
    }

    /** Run a shell snippet as a specific user, suppressing output. On failure, reports the captured output before throwing. */
    public void runAsUserQuiet(String user, String script, String failureMessage) {
        var result = incus.execInContainer(name, user, script);
        if (!result.success()) {
            throw failed(out, "The failed command printed:", failureMessage + " (exit code " + result.exitCode() + ")", result);
        }
    }

    /** Install packages via dnf. */
    public void dnfInstall(String failureMessage, String... packages) {
        var command = new String[packages.length + 3];
        command[0] = "dnf";
        command[1] = "install";
        command[2] = "-y";
        System.arraycopy(packages, 0, command, 3, packages.length);
        runInteractive(failureMessage, command);
    }

    /** Default heredoc delimiter; lengthened if the content itself contains it. */
    private static final String HEREDOC_MARKER = "INCUS_EOF";

    /** Write content to a file inside the container. */
    public void writeFile(String path, String content) {
        sh(heredoc("mkdir -p \"$(dirname " + shellQuote(path) + ")\" && cat > " + shellQuote(path),
                content.strip()))
                .assertSuccess("Failed to write file in container: " + path);
    }

    /**
     * {@code command} fed {@code content} verbatim on stdin, as a quoted heredoc. The result
     * ends with the delimiter line and no newline, so a caller continuing the script starts
     * the next command on a new line.
     */
    public static String heredoc(String command, String content) {
        var marker = heredocMarker(content);
        return command + " << '" + marker + "'\n" + content + "\n" + marker;
    }

    /**
     * Pick a delimiter that no line of {@code content} equals. Content reaching here is
     * often user-authored (tool {@code files:} blocks, template env, skills, agent notes),
     * so a fixed delimiter could be closed early by the content itself — truncating the
     * file and running its remainder as shell. Extending the marker keeps the content
     * byte-for-byte intact, unlike escaping it.
     */
    private static String heredocMarker(String content) {
        var marker = HEREDOC_MARKER;
        while (containsLine(content, marker)) {
            marker += "_";
        }
        return marker;
    }

    private static boolean containsLine(String content, String marker) {
        for (var line : content.split("\n", -1)) {
            if (line.equals(marker)) return true;
        }
        return false;
    }

    /** Set ownership recursively. */
    public void chown(String path, String owner) {
        exec("chown", "-R", owner, path);
    }

    /** Push a directory recursively into the container. */
    public void filePushRecursive(String sourceDir, String destPath) {
        incus.filePushRecursive(sourceDir, name, destPath);
    }

    /** Push a single file into the container at the exact destination path. */
    public void filePush(String sourcePath, String destPath) {
        incus.filePush(sourcePath, name, destPath);
    }

    /** Append a line to agentuser's .bashrc. */
    public void appendToProfile(String line) {
        sh("printf '%s\\n' " + shellQuote(line) + " >> /home/agentuser/.bashrc")
                .assertSuccess("Failed to append to /home/agentuser/.bashrc");
    }

    /** Get the container's architecture (e.g. "x86_64", "aarch64"). Cached after first lookup. */
    public String getArchitecture() {
        if (architecture == null) {
            architecture = incus.getInstanceArchitecture(name);
        }
        return architecture;
    }

    public static String shellQuote(String value) {
        return "'" + java.util.Objects.requireNonNull(value, "value").replace("'", "'\"'\"'") + "'";
    }
}
