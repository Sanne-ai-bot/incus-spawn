package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.util.BuildOutput;

/**
 * The network wait, system update and git fetch steps shared by {@code isx update-all} and
 * {@code isx project update}. Each returns false, after reporting it, when the step failed.
 */
final class GuestUpdate {

    /**
     * Fetches every git repository directly under agentuser's home. Exits non-zero, naming each
     * repository, when any fetch failed. Nothing may prompt (there is nobody to answer), and
     * fetches are quiet so stderr holds only what went wrong.
     */
    static final String GIT_FETCH_SCRIPT = "export GIT_TERMINAL_PROMPT=0 GIT_SSH_COMMAND='ssh -o BatchMode=yes';"
            + " failed=0; for d in ~/*/; do if [ -d \"$d/.git\" ]; then"
            + " echo \"  Fetching $d\"; git -C \"$d\" fetch --all --quiet"
            + " || { echo \"git fetch failed in $d\" >&2; failed=1; }; fi; done; exit $failed";

    /**
     * Empties /tmp and /var/tmp, hidden files included, of a template about to be saved: /tmp is
     * on disk (#1561), so what a step left there would reach every branch. {@code -xdev} keeps
     * it on their own filesystem (isx mounts nothing there: host resources may not target /tmp).
     */
    static final String EMPTY_TMP_SCRIPT = "find /tmp /var/tmp -xdev -mindepth 1 -delete";

    private GuestUpdate() {
    }

    /**
     * Waits for the started template's network ({@link GuestNetwork}). It boots on DHCP, so its
     * lease may not be in place yet, and every later step needs the network (#1734).
     */
    static boolean network(IncusClient incus, String name, MachineType machineType) {
        BuildOutput.stepStart("Waiting for network...");
        var failure = GuestNetwork.waitUntilOnline(incus, name, machineType);
        if (failure != null) {
            BuildOutput.stepFail(failure);
            return false;
        }
        BuildOutput.stepDone();
        return true;
    }

    static boolean system(IncusClient incus, String name) {
        BuildOutput.stepStart("Running system updates...");
        return finish(incus.shellExec(name, "dnf", "update", "-y"), "dnf update -y");
    }

    /**
     * Not a step that fails: a leftover it cannot remove is no reason to keep the old template,
     * so it is only warned about.
     */
    static void emptyTmp(IncusClient incus, String name) {
        try {
            var result = incus.shellExec(name, "sh", "-c", EMPTY_TMP_SCRIPT);
            if (!result.success()) {
                BuildOutput.warn("Could not empty /tmp in " + name + "; what is left there stays in the template.",
                        result.stderr().strip());
            }
        } catch (RuntimeException e) {
            BuildOutput.warn("Could not empty /tmp in " + name + ": " + e.getMessage());
        }
    }

    static boolean gitRepos(IncusClient incus, String name) {
        BuildOutput.stepStart("Updating git repositories...");
        return finish(incus.execInContainer(name, "agentuser", GIT_FETCH_SCRIPT), "git fetch");
    }

    /** Lines of a failed step's stdout, and of its stderr, shown. */
    private static final int TAIL_LINES = 30;

    /**
     * Ends the step started for {@code what}: done, or failed with its exit code and the tails of
     * its stdout (build tools such as {@code mvn -q} and {@code npm ci} report errors there) and stderr.
     */
    static boolean finish(IncusClient.ExecResult result, String what) {
        if (!result.success()) {
            var output = new StringBuilder(what + " failed (exit code " + result.exitCode() + ")");
            for (var stream : new String[] {result.stdout(), result.stderr()}) {
                if (!stream.isBlank()) output.append('\n').append(BuildOutput.lastLines(stream, TAIL_LINES));
            }
            BuildOutput.stepFail(output.toString());
            return false;
        }
        BuildOutput.stepDone();
        return true;
    }
}
