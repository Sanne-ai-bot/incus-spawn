"""Shared helpers for the bench/ scripts that drive the isx CLI."""
import fcntl, os, re, select, signal, statistics, struct, subprocess, termios, time


class NoPrompt(Exception):
    """The command never reached a usable shell prompt (or the screen it waited for)."""


# Time to a usable prompt, which is what a user actually waits for. The command runs on a
# pseudo-terminal (isx reads /dev/tty and puts it in raw mode, so it needs a controlling one),
# and this line is typed at once. It sits in the terminal's input queue until the shell inside
# the instance reads it, so the marker prints exactly when the shell is ready. The typed text
# is echoed back as "$((6*7))", and only a shell that ran it prints 42 -- so the echo of the
# input cannot be mistaken for the output.
PROMPT_INPUT = b"echo ISXBENCH_$((6*7))_READY; exit\r"
PROMPT_MARKER = b"ISXBENCH_42_READY"
# Terminal escapes (CSI, OSC, two-byte), stripped to match screen text and to make a failure's
# output readable.
ANSI = re.compile(rb"\x1b(\[[0-9;?<>=]*[ -/]*[@-~]|\][^\x07\x1b]*(\x07|\x1b\\)|[@-Z\\-_])")


def run_to_prompt(cmd, timeout_s, env=None):
    """Milliseconds until the shell in the instance runs a typed line, which then exits it."""
    return run_on_pty(cmd, timeout_s, lambda out: PROMPT_MARKER in out, typed=PROMPT_INPUT, env=env)[0]


def run_to_screen(cmd, timeout_s, pattern, quit_keys=(b"\x03", b"\x03")):
    """Milliseconds until `pattern` (a bytes regex) appears on the screen, escapes stripped.

    For a full-screen program, such as Claude Code, that never runs a typed shell command. Once it
    matched, each of `quit_keys` is typed half a second apart, so the program exits as a user's
    would and isx restores the terminal.
    """
    regex = re.compile(pattern)
    return run_on_pty(cmd, timeout_s, lambda out: regex.search(ANSI.sub(b"", out)),
                      quit_keys=quit_keys)[0]


def run_typed(cmd, timeout_s, typed, marker):
    """Types `typed` into the shell `cmd` opens and returns its output once `marker` is printed."""
    return run_on_pty(cmd, timeout_s, lambda out: marker in out, typed=typed)[1]


def run_on_pty(cmd, timeout_s, done, typed=b"", quit_keys=(), env=None):
    master, slave = os.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
    child_env = dict(os.environ if env is None else env, TERM="xterm-256color")
    # Inside tmux, isx renames the window and the instance may attach tmux itself; neither
    # belongs in a measurement, and the user's own tmux must not be touched.
    child_env.pop("TMUX", None)
    start = time.perf_counter_ns()
    proc = subprocess.Popen(cmd, stdin=slave, stdout=slave, stderr=slave, env=child_env,
                            start_new_session=True,
                            preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0))
    os.close(slave)
    if typed:
        os.write(master, typed)
    output, elapsed_ms = b"", None
    deadline = time.monotonic() + timeout_s
    try:
        while elapsed_ms is None and time.monotonic() < deadline:
            ready, _, _ = select.select([master], [], [], 0.1)
            if not ready:
                if proc.poll() is not None:
                    break
                continue
            try:
                chunk = os.read(master, 65536)
            except OSError:  # EIO: the child closed the terminal
                break
            # Taken before `done` looks at the output, so matching the last chunk is not timed.
            read_at = time.perf_counter_ns()
            output += chunk
            if done(output):
                elapsed_ms = (read_at - start) / 1e6
        if elapsed_ms is not None:
            for key in quit_keys:
                time.sleep(0.5)
                os.write(master, key)
            # The typed `exit` (or the quit keys) ends the session; keep draining so isx can
            # restore the terminal.
            end = time.monotonic() + 30
            while proc.poll() is None and time.monotonic() < end:
                if select.select([master], [], [], 0.1)[0]:
                    try:
                        os.read(master, 65536)
                    except OSError:
                        break
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGKILL)
        proc.wait()
        os.close(master)
    if elapsed_ms is None:
        tail = ANSI.sub(b"", output).decode("utf-8", "replace").strip()[-2000:]
        why = (f"not ready within {timeout_s}s" if proc.returncode == -signal.SIGKILL
               else f"exited {proc.returncode} before it was ready")
        raise NoPrompt(f"{' '.join(cmd)}: {why}. Last output:\n{tail}")
    return elapsed_ms, output


def stats(samples):
    ordered = sorted(samples)
    p90 = ordered[min(len(ordered) - 1, int(round(0.9 * (len(ordered) - 1))))]
    return {"medianMs": round(statistics.median(ordered), 1), "p90Ms": round(p90, 1),
            "minMs": round(ordered[0], 1), "runs": len(ordered)}


def report(op, s, width=16):
    print(f"  {op:<{width}} {s['medianMs']:>8.1f} ms median  {s['p90Ms']:>8.1f} p90  "
          f"{s['minMs']:>8.1f} min  ({s['runs']} runs)")
