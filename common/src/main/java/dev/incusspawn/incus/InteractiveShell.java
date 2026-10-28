package dev.incusspawn.incus;

import dev.incusspawn.incus.IncusClient.ShellPrep;
import dev.incusspawn.tool.ShellMenu;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.TerminalStreams;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The interactive shell into an instance: the PTY session and its reconnects, the host terminal's
 * title, tmux window and terminfo. Split out of {@link IncusClient}, whose
 * {@code interactiveShell} methods open it.
 */
final class InteractiveShell {

    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    private final IncusClient incus;

    InteractiveShell(IncusClient incus) {
        this.incus = incus;
    }

    private static final java.util.regex.Pattern SIMPLE_COMMAND =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_./ -]+$");

    static String zmxCommand(String shellCommand) {
        if (SIMPLE_COMMAND.matcher(shellCommand).matches()) {
            return shellCommand;
        }
        var escaped = shellCommand.replace("'", "'\\''");
        return "bash -c '" + escaped + "'";
    }

    public void interactiveShell(String container, String user, ShellPrep prep, ShellMenu menu) {
        if (!IncusClient.hasTerminal()) throw new NoTerminalForShellException(container);
        session(container, user, prep, menu, System.getenv("TMUX") != null, System.getenv("TERM"));
    }

    /**
     * The session on the terminal {@link #interactiveShell} checked is there; a seam for tests,
     * which pass {@code inTmux} so a test run in tmux does not rename its window, and the host's
     * {@code term} that a guest without its terminfo is given. The title and the
     * reconnect notice go through the bound output, whose terminal writes the title only on a
     * terminal; the status bar draws on the terminal's own stdout, which the guest is handed.
     */
    void session(String container, String user, ShellPrep prep, ShellMenu menu, boolean inTmux, String term) {
        var title = "isx:" + container;
        BuildOutput.windowTitle(title);

        String savedWindowName = null;
        String savedStatusRight = null;
        if (inTmux) {
            savedWindowName = hostExecCapture("tmux", "display-message", "-p", "#W");
            hostExecQuiet("tmux", "rename-window", title);
            if (prep.subnetDiagnostic() != null) {
                savedStatusRight = hostExecCapture("tmux", "show-option", "-v", "status-right");
                if (savedStatusRight == null) savedStatusRight = "";
                hostExecQuiet("tmux", "set-option", "status-right",
                        "#[bg=yellow,fg=black,bold] ⚠ Bridge subnet conflict — run 'isx init' #[default]");
            }
        }

        try {
            // Inside the try, so a failed propagation still restores the title and tmux window
            if (!prep.terminfoHandled()) {
                propagateTerminfo(container, term);
            }

            var homeDir = "/home/" + user;
            var targetCwd = prep.workdir() != null ? prep.workdir() : homeDir;

            String innerScript;
            if (prep.shellCommand() != null && prep.autoAttachZmx()) {
                var zmxCmd = zmxCommand(prep.shellCommand());
                innerScript =
                        "if command -v zmx >/dev/null 2>&1; then "
                        + "exec zmx attach isx " + zmxCmd
                        + "; fi; " + prep.shellCommand() + " || exec bash --login";
            } else if (prep.shellCommand() != null) {
                innerScript = prep.shellCommand() + " || exec bash --login";
            } else if (prep.autoAttachTmux() && !inTmux) {
                innerScript =
                        "if command -v tmux >/dev/null 2>&1; then "
                        + "infocmp \"$TERM\" >/dev/null 2>&1 || export TERM=xterm-256color; "
                        + "exec tmux new-session -A -s isx; fi; exec bash --login";
            } else if (prep.autoAttachZmx() && !inTmux) {
                innerScript =
                        "if command -v zmx >/dev/null 2>&1; then "
                        + "exec zmx attach isx bash --login; fi; exec bash --login";
            } else {
                innerScript = "exec bash --login";
            }

            // Wrap in su -l so the session goes through PAM, which calls
            // initgroups() (activating supplementary groups like incus-admin)
            // and starts the systemd user instance (needed by rootless podman).
            // -P (--pty) makes su allocate its own PTY with a proper setsid +
            // TIOCSCTTY, giving the inner bash a controlling terminal — without
            // it, tcgetpgrp() returns -1 and bash prints "cannot set terminal
            // process group / no job control in this shell".
            // su -l does cd $HOME, so we prepend cd to restore targetCwd.
            var script = "cd " + Container.shellQuote(targetCwd) + " && "
                    + IncusClient.LOGIN_PATH_PREFIX + innerScript;
            var shellArgs = List.of("su", "-l", "-P", user, "-c", script);

            var env = Map.of("HOME", homeDir);

            var statusBar = ShellMenu.enabled() ? new ShellStatusBar(container, TerminalStreams.out()) : null;
            if (statusBar != null) statusBar.setMenuActions(menu.actions(), menu.context());

            reconnecting(() -> {
                var size = IncusApi.terminalSize();
                return incus.http().execPty(container, shellArgs, 0, 0, null, env, size[0], size[1], statusBar);
            }, InteractiveShell::pause, () -> System.nanoTime() / 1_000_000);
        } finally {
            if (inTmux && savedWindowName != null) {
                hostExecQuiet("tmux", "rename-window", savedWindowName);
            }
            if (savedStatusRight != null) {
                if (savedStatusRight.isEmpty()) {
                    hostExecQuiet("tmux", "set-option", "-u", "status-right");
                } else {
                    hostExecQuiet("tmux", "set-option", "status-right", savedStatusRight);
                }
            }
            BuildOutput.windowTitle("");
        }
    }

    /** One connection to the guest's PTY: true when it was lost after the session ran, false when the session ended. */
    @FunctionalInterface
    interface Connection {
        boolean run();
    }

    /** The wait before a reconnect: false when it was interrupted, which ends the shell. */
    @FunctionalInterface
    interface Pause {
        boolean sleep(long millis);
    }

    /** The longest wait between reconnects; a session that stayed up longer was a working shell. */
    static final long LONGEST_WAIT_MS = 10_000;

    /**
     * Run {@code connection} until its session ends, reconnecting after a lost connection.
     * A connection that fails to come back, or comes back and drops again before
     * {@link #LONGEST_WAIT_MS}, is retried with a growing wait, up to
     * {@link #MAX_RECONNECT_ATTEMPTS} times. A session that stayed up longer and then dropped is
     * a loss of its own, which starts the count again. Each loss is one warning, which a TUI
     * shell's log keeps; a retry is a note, so a flaky link does not fill the log with copies of
     * one event. {@code clock} is in milliseconds.
     */
    static void reconnecting(Connection connection, Pause pause, LongSupplier clock) {
        int attempt = 0;
        while (true) {
            long started = clock.getAsLong();
            try {
                if (!connection.run()) return;
                if (clock.getAsLong() - started >= LONGEST_WAIT_MS) attempt = 0;
            } catch (IncusException e) {
                if (!hasIOExceptionCause(e) || attempt >= MAX_RECONNECT_ATTEMPTS) throw e;
            }
            if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                throw new IncusException("The shell's connection dropped again each time it came back");
            }
            if (attempt == 0) {
                // The guest's last line may be unfinished: start the notice on a line of its own
                TerminalStreams.out().println();
                BuildOutput.warn("Connection lost — reconnecting...");
            } else {
                BuildOutput.note("Still reconnecting (attempt " + (attempt + 1) + " of " + MAX_RECONNECT_ATTEMPTS + ")...");
            }
            if (!pause.sleep(Math.min(1000L << attempt, LONGEST_WAIT_MS))) return;
            attempt++;
        }
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean hasIOExceptionCause(Throwable t) {
        while (t != null) {
            if (t instanceof java.io.IOException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static String hostExecCapture(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            return process.exitValue() == 0 ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void hostExecQuiet(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start().waitFor();
        } catch (IOException e) {
            // best-effort
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void propagateTerminfo(String container, String term) {
        if (term == null || term.isEmpty()) return;
        var check = incus.shellExec(container, "infocmp", term);
        if (check.exitCode() == 0) return;
        String terminfo = hostExecCapture("infocmp", "-x", term);
        if (terminfo == null) return;
        incus.shellExec(container, "sh", "-c",
                "cat <<'TERMINFO_EOF' | tic -x -\n" + terminfo + "\nTERMINFO_EOF");
    }
}
