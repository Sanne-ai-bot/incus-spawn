#!/bin/bash
# bench/claude-start.sh — How long a user waits for Claude Code's first screen in an instance
#
# bench/cli.sh measures to a shell prompt and keeps a template's default action out (--shell).
# For a template whose default action is Claude Code, what a user waits for is Claude's first
# screen, so this measures that, and splits it: the isx part (the same instance's shell prompt),
# the action's pre-launch scan of the session history, and Node starting Claude (`claude --version`).
#
# Requires: working isx setup (isx init), running Incus daemon (on macOS: the VM, isx vm start)
#           and proxy, a built template whose default action is Claude Code (--from), python3.
#           It drives the installed `isx` (or --isx) and builds nothing.
#
# Usage:
#   bench/claude-start.sh --from=tpl-agent
#   bench/claude-start.sh --from=tpl-agent --sessions=1000 --restart-proxy
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results/claude"

LABEL=""
RUNS=10
BRANCH_RUNS=3
SESSIONS=300
FROM=""
ISX="isx"
WAIT_FOR='Claude Code ?v[0-9]'
RESTART_PROXY=false

while [ $# -gt 0 ]; do
    case "$1" in
        --label=*) LABEL="${1#--label=}" ;;
        --runs=*) RUNS="${1#--runs=}" ;;
        --branch-runs=*) BRANCH_RUNS="${1#--branch-runs=}" ;;
        --sessions=*) SESSIONS="${1#--sessions=}" ;;
        --from=*) FROM="${1#--from=}" ;;
        --isx=*) ISX="${1#--isx=}" ;;
        --wait-for=*) WAIT_FOR="${1#--wait-for=}" ;;
        --restart-proxy) RESTART_PROXY=true ;;
        --help|-h)
            echo "Usage: bench/claude-start.sh --from=TEMPLATE [--runs=N] [--branch-runs=N] [--sessions=N] [--wait-for=REGEX] [--isx=CMD] [--restart-proxy] [--label=NAME]"
            echo ""
            echo "Measures the time to Claude Code's first screen after isx branch and isx run, on a"
            echo "throwaway instance branched from TEMPLATE and destroyed on exit."
            echo ""
            echo "Options:"
            echo "  --from=TEMPLATE  A built template whose default action is Claude Code (required)"
            echo "  --runs=N         Timed runs per operation (default 10, after 1 warmup)"
            echo "  --branch-runs=N  Full branches timed (default 3; each creates an instance)"
            echo "  --sessions=N     Synthetic session history the pre-launch scan is also timed over (default 300)"
            echo "  --wait-for=RE    Text that marks Claude's first screen, escapes stripped (default '$WAIT_FOR')"
            echo "  --isx=CMD        The isx to drive (default: isx on PATH; e.g. 'java -jar cli/target/quarkus-app/quarkus-run.jar')"
            echo "  --restart-proxy  Also restart the proxy service and time the first and second Claude start after it"
            echo "  --label=NAME     Tag results with a label"
            exit 0
            ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "Error: $*" >&2; exit 1; }

[ -n "$FROM" ] || die "--from=TEMPLATE is required: a template whose default action is Claude Code"
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || die "--runs must be a positive integer"
[[ "$BRANCH_RUNS" =~ ^[1-9][0-9]*$ ]] || die "--branch-runs must be a positive integer"
[[ "$SESSIONS" =~ ^[0-9]+$ ]] || die "--sessions must be a non-negative integer"
command -v python3 &>/dev/null || die "python3 not found on PATH"
# shellcheck disable=SC2086  # "java -jar <path>" for the JVM
$ISX list -q >/dev/null 2>&1 || die "'$ISX list -q' failed. Is isx set up and Incus running?"

INSTANCE="isx-claude-bench-$$"
cleanup() {
    local leftover
    # shellcheck disable=SC2086
    for leftover in $($ISX list -q 2>/dev/null | grep -E "^$INSTANCE(-|\$)"); do
        echo ""
        echo "Destroying $leftover..."
        # shellcheck disable=SC2086
        $ISX destroy "$leftover" --skip-confirmation >/dev/null 2>&1 || \
            echo "Warning: could not destroy $leftover; remove it with: isx destroy $leftover" >&2
    done
}
trap cleanup EXIT

GIT_SHA="$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
export SCRIPT_DIR RESULTS_DIR LABEL RUNS BRANCH_RUNS SESSIONS FROM ISX WAIT_FOR RESTART_PROXY INSTANCE GIT_SHA

python3 - <<'PY'
import base64, json, os, re, shlex, subprocess, sys, time
from datetime import datetime, timezone

sys.dont_write_bytecode = True  # keep bench/ free of __pycache__
sys.path.insert(0, os.environ["SCRIPT_DIR"])
import isxbench

results = {}

env = os.environ
runs, branch_runs, sessions = int(env["RUNS"]), int(env["BRANCH_RUNS"]), int(env["SESSIONS"])
instance, source = env["INSTANCE"], env["FROM"]
isx = shlex.split(env["ISX"])
screen = env["WAIT_FOR"].encode()

# What the Claude Code action runs before Claude itself: ClaudeSetup.actionCommand(":", ":"),
# which BenchClaudeStartScriptTest keeps this equal to. The bench cannot ask isx for it.
PRE_LAUNCH = r"""d=$(find ~/.claude/projects -name '*.jsonl' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-); r=; if [ -n "$d" ]; then r=1; c=$(grep -m1 -o '"cwd":"[^"]*"' "$d" | cut -d'"' -f4); [ -n "$c" ] && { [ -d "$c" ] && cd "$c" || r=; }; fi; if [ -n "$r" ]; then :; else :; fi"""

# Runs in the instance's login shell, as its user. The synthetic history goes into a scratch
# HOME, so the instance's own is never touched: N sessions over 10 projects, each with two
# subagent transcripts beside it, as Claude Code writes them. The base image has no awk.
GUEST = r"""
t() { local i s e; for i in $(seq 0 RUNS); do s=$(date +%s%N); ( eval "$2" ) >/dev/null 2>&1; e=$(date +%s%N); [ "$i" -gt 0 ] && echo "ISXBENCH $1 $(( (e - s) / 1000 ))"; done; }
pre='PRE_LAUNCH'
t preLaunchOwn "$pre"
t claudeVersion 'claude --version'
h=$(mktemp -d); pad=$(head -c 2000 /dev/zero | tr '\0' x)
for i in $(seq 1 SESSIONS); do
  p="$h/.claude/projects/-home-proj$((i % 10))"; id=$(printf '%08d-0000-4000-8000-%012d' "$i" "$i"); mkdir -p "$p/$id/subagents"
  { echo '{"type":"file-history-snapshot"}'; for l in $(seq 1 40); do echo "{\"type\":\"user\",\"cwd\":\"/home/proj$((i % 10))\",\"message\":\"$pad\"}"; done; } > "$p/$id.jsonl"
  cp "$p/$id.jsonl" "$p/$id/subagents/agent-a.jsonl"; cp "$p/$id.jsonl" "$p/$id/subagents/agent-b.jsonl"
done
HOME=$h t preLaunchSynthetic "$pre"
rm -rf "$h"
echo ISXBENCH_DONE
""".replace("RUNS", str(runs)).replace("SESSIONS", str(sessions)).replace("PRE_LAUNCH", PRE_LAUNCH.replace("'", "'\\''"))

def run(cmd, expect_exit=0):
    proc = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if proc.returncode != expect_exit:
        sys.exit(f"\nError: {' '.join(cmd)} exited {proc.returncode}:\n{(proc.stdout + proc.stderr).strip()}")

def timed(what, *args):
    try:
        return what(*args)
    except isxbench.NoPrompt as e:
        sys.exit(f"\nError: {e}")

def record(key, samples):
    results[key] = isxbench.stats(samples)
    isxbench.report(key, results[key], 20)

def to_claude(cmd):
    return timed(isxbench.run_to_screen, cmd, 120, screen)

print(f"Branching {instance} from {source} (timed to Claude's first screen)...")
samples = []
for i in range(branch_runs):
    name = instance if i == 0 else f"{instance}-{i}"
    samples.append(to_claude(isx + ["branch", name, "--from", source]))
    if i:
        run(isx + ["destroy", name, "--skip-confirmation"])
record("branchToClaude", samples)
# The same branch to a shell prompt instead of the default action, for the split.
samples = []
for i in range(branch_runs):
    name = f"{instance}-shell-{i}"
    samples.append(timed(isxbench.run_to_prompt, isx + ["branch", name, "--from", source, "--shell"], 120))
    run(isx + ["destroy", name, "--skip-confirmation"])
record("branchToPrompt", samples)

# The instance is running: what `isx run` (the TUI's Enter) costs every later time.
run_cmd = isx + ["run", instance]
to_claude(run_cmd)
record("runToClaude", [to_claude(run_cmd) for _ in range(runs)])
shell_cmd = isx + ["shell", instance]
timed(isxbench.run_to_prompt, shell_cmd, 120)
record("shellToPrompt", [timed(isxbench.run_to_prompt, shell_cmd, 120) for _ in range(runs)])

# Inside the instance, timed on its own clock, so no exec or terminal is included.
script = base64.b64encode(GUEST.encode()).decode()
typed = f"echo {script} | base64 -d > /tmp/isxbench-claude.sh; bash /tmp/isxbench-claude.sh; exit\r".encode()
output = timed(isxbench.run_typed, shell_cmd, 600, typed, b"ISXBENCH_DONE")
text = isxbench.ANSI.sub(b"", output).decode("utf-8", "replace")
# One sample per line: a terminal that wraps long lines (tmux, if the instance attaches it)
# cannot cut one.
guest = {}
for key, us in re.findall(r"^ISXBENCH (\w+) ([0-9]+)$", text.replace("\r", ""), re.M):
    guest.setdefault(key, []).append(int(us) / 1000)
for key, samples in guest.items():
    record(key, samples)
for key in ("preLaunchOwn", "claudeVersion", "preLaunchSynthetic"):
    if key not in results:
        sys.exit(f"\nError: no {key} figure from the instance. Its output:\n{text[-2000:]}")

if env["RESTART_PROXY"] == "true":
    print("Restarting the proxy...")
    run(isx + ["proxy", "restart"])
    first = to_claude(run_cmd)
    second = to_claude(run_cmd)
    results["afterProxyRestart"] = {"firstMs": round(first, 1), "secondMs": round(second, 1)}
    print(f"  {'afterProxyRestart':<20} {first:>8.1f} ms first  {second:>8.1f} ms second")

os.makedirs(env["RESULTS_DIR"], exist_ok=True)
result = {
    "label": env["LABEL"],
    "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "gitSha": env["GIT_SHA"],
    "source": source,
    "syntheticSessions": sessions,
    "results": results,
}
path = os.path.join(env["RESULTS_DIR"], f"{env['GIT_SHA']}-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json")
with open(path, "w") as f:
    json.dump(result, f, indent=2)
    f.write("\n")
print(f"\nResults saved to: {path}")
PY

echo ""
echo "Done."
