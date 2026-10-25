"""Interleaved A/B of builds: bench/run.sh or bench/cli.sh, round after round, in an order that
alternates direction, so drift over the session falls on every build alike.

A build is NAME=PATH: a proxy binary for `proxy`, a CLI binary for `cli`. The first one named is
the baseline the others are compared with. Each run is an ordinary run.sh or cli.sh run, its
result file kept under bench/results/; this prints the medians across rounds and writes them,
with every run's file, to bench/results/ab/<time>.json.

  python3 -I bench/ab.py proxy --rounds=3 --loads=maven,anthropic --cli=BIN o3=BIN pgo=BIN
  python3 -I bench/ab.py cli --rounds=3 --from=tpl-isx-bench os=BIN o3pgo=BIN ospgo=BIN

`proxy` needs the proxy service stopped (run.sh's rule); `cli` needs a running proxy built
from the same commit as the CLIs (cli.sh's rule). Only builds of one commit compare.

The output's `provenance` records what was measured: checkout, each build's commit, GraalVM and
native build flags, and the host. bench/README.md says where the flags are read from.
"""
import argparse, hashlib, json, os, platform, re, shutil, statistics, subprocess, sys, tempfile, time
from datetime import datetime, timezone

BENCH = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(BENCH, "results", "ab")


def order(builds, rnd):
    return builds if rnd % 2 == 0 else list(reversed(builds))


def run(cmd):
    print(f"\n$ {' '.join(cmd)}", flush=True)
    proc = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, text=True)
    out = proc.stdout + proc.stderr
    saved = re.findall(r"Results saved to: (\S+)", out)
    if proc.returncode != 0 or not saved:
        sys.exit(f"Error: exited {proc.returncode}:\n{out.strip()[-3000:]}")
    with open(saved[-1]) as f:
        return saved[-1], json.load(f)


def capture(cmd, cwd=None):
    """A command's stdout, stripped, or None when it cannot run or fails."""
    try:
        proc = subprocess.run(cmd, cwd=cwd, stdin=subprocess.DEVNULL, capture_output=True,
                              text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return proc.stdout.strip() if proc.returncode == 0 else None


def checkout():
    commit = capture(["git", "rev-parse", "HEAD"], cwd=BENCH)
    status = capture(["git", "status", "--porcelain"], cwd=BENCH)
    return {"commit": commit, "dirty": None if status is None else bool(status),
            "nativeImageOnPath": capture(["native-image", "--version"])}


def build_report(path):
    """The native-image build report of a binary, or None: beside it, or where Quarkus left it."""
    for binary in dict.fromkeys((path, os.path.realpath(path))):
        name = os.path.basename(binary)
        for candidate in (f"{binary}-build-output-stats.json",
                          os.path.join(os.path.dirname(binary), re.sub(r"-runner$", "", name)
                                       + "-native-image-source-jar", f"{name}-build-output-stats.json")):
            if os.path.isfile(candidate):
                try:
                    with open(candidate) as f:
                        return candidate, json.load(f).get("general_info") or {}
                except (OSError, ValueError, AttributeError) as e:
                    print(f"Warning: {candidate}: unreadable ({e}), so its build flags go unrecorded.",
                          file=sys.stderr)
                    return candidate, {}
    return None, None


def build_info(path):
    """What a binary says about itself (`--version`: its commit and GraalVM) and how it was built."""
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    # In a scratch directory: an instrumented build writes a profile wherever it exits.
    with tempfile.TemporaryDirectory() as scratch:
        version = capture([path, "--version"], cwd=scratch) or ""
    lines = version.splitlines()
    commit = re.search(r"\(([0-9a-f]{7,40})\)", lines[0]) if lines else None
    graalvm = next((m.group(1) for line in lines if (m := re.search(r"GraalVM ([^)]+)", line))), None)
    report, general = build_report(path)
    general = general or {}
    return {"sha256": digest.hexdigest(), "bytes": os.path.getsize(path),
            "version": lines[0] if lines else None, "commit": commit.group(1) if commit else None,
            "graalvm": graalvm, "buildReport": report,
            # optimization_level, march and pgo (absent when off), as native-image reported them.
            "graalCompiler": general.get("graal_compiler"),
            "garbageCollector": general.get("garbage_collector"),
            "cCompiler": general.get("c_compiler")}


def first_match(path, pattern):
    try:
        with open(path) as f:
            return next((m.group(1).strip() for line in f if (m := re.match(pattern, line))), None)
    except OSError:
        return None


def host():
    if sys.platform == "darwin":
        version = capture(["sw_vers", "-productVersion"])
        os_name = f"macOS {version}" if version else None
        cpu = capture(["sysctl", "-n", "machdep.cpu.brand_string"])
        # macOS says whether it runs under a hypervisor, not which one.
        vm = {"1": "unnamed", "0": False}.get(capture(["sysctl", "-n", "kern.hv_vmm_present"]))
        container = None
        memory = capture(["sysctl", "-n", "hw.memsize"])
        memory_kb = int(memory) // 1024 if memory else None
    else:
        os_name = first_match("/etc/os-release", r'PRETTY_NAME="?([^"\n]*)')
        cpu = first_match("/proc/cpuinfo", r"model name\s*:\s*(.*)")
        # systemd-detect-virt prints "none" and exits 1 when there is none; capture() reads that as None.
        detect = shutil.which("systemd-detect-virt") is not None
        vm = capture(["systemd-detect-virt", "--vm"]) or (False if detect else None)
        container = capture(["systemd-detect-virt", "--container"]) or (False if detect else None)
        kb = first_match("/proc/meminfo", r"MemTotal:\s*(\d+)")
        memory_kb = int(kb) if kb else None
    return {"os": os_name, "kernel": platform.release(), "arch": platform.machine(), "cpu": cpu,
            # The CPUs this process may run on, which a taskset or a cgroup can narrow.
            "cores": len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else os.cpu_count(),
            "memoryKb": memory_kb,
            # The hypervisor's name (kvm, ...) in a VM, and the container's (lxc, ...) in one;
            # false when there is none, null when unknown.
            "vm": vm, "container": container}


def provenance(builds):
    infos = {name: build_info(path) for name, path in builds}
    for name, path in builds:
        if not infos[name]["buildReport"]:
            print(f"Warning: {name}: no build report beside {path}, so its build flags go "
                  f"unrecorded. Copy the build's *-build-output-stats.json to "
                  f"{path}-build-output-stats.json.", file=sys.stderr)
    commits = {info["commit"] for info in infos.values()}
    if None in commits:
        print("Warning: a build's commit is unknown (its --version names none).", file=sys.stderr)
    if len(commits - {None}) > 1:
        print("Warning: the builds were made from different commits; only builds of one commit compare.",
              file=sys.stderr)
    return {"checkout": checkout(), "builds": infos, "host": host()}


def proxy_metrics(result):
    t = result.get("throughput", {})
    metrics = {
        "binaryBytes": result["binarySizeBytes"],
        "startupMs": result["startupMs"],
        "idleRssKb": result["idleRssKb"],
        "peakRssKb": result["peakRssKb"],
        "bestReqPerSec": t.get("meanReqPerSec", 0),
    }
    for rung in t.get("ladder", []):
        metrics[f"{rung['phase']}ReqPerSec"] = rung["meanReqPerSec"]
        metrics[f"{rung['phase']}P50Us"] = rung["p50Us"]
    return metrics


def cli_metrics(result, binary):
    (runtime,) = result["runtimes"].values()
    metrics = {"binaryBytes": os.path.getsize(binary)}
    for op, s in runtime.items():
        metrics[f"{op}Ms"] = s["medianMs"]
        if "mibPerSec" in s:
            metrics[f"{op}MiBPerSec"] = s["mibPerSec"]
    return metrics


def summarize(samples):
    """samples: {series: {build: [metrics dict per round]}} -> medians."""
    return {series: {build: {k: statistics.median(r[k] for r in rounds if k in r)
                             for k in dict.fromkeys(k for r in rounds for k in r)}
                     for build, rounds in per_build.items()}
            for series, per_build in samples.items()}


def print_table(medians, builds):
    base = builds[0]
    for series, per_build in medians.items():
        print(f"\n=== {series} (median of rounds; delta against {base}) ===")
        print(f"  {'metric':<22}" + "".join(f"{b:>22}" for b in builds))
        for key in per_build[base]:
            row = f"  {key:<22}"
            for b in builds:
                v = per_build[b].get(key)
                if v is None:
                    row += f"{'-':>22}"
                elif b == base or not per_build[base][key]:
                    row += f"{v:>22,.1f}"
                else:
                    pct = (v - per_build[base][key]) / per_build[base][key] * 100
                    row += f"{f'{v:,.1f} ({pct:+.1f}%)':>22}"
            print(row)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("side", choices=["proxy", "cli"])
    parser.add_argument("builds", nargs="+", metavar="NAME=PATH")
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--loads", default="maven,saturate,anthropic,vertex", help="proxy: run.sh loads")
    parser.add_argument("--cli", help="proxy: the CLI run.sh branches the model loads' instance with")
    parser.add_argument("--from", dest="source", default="tpl-isx-bench", help="cli: template to branch")
    parser.add_argument("--runs", type=int, default=20, help="cli: timed runs per operation")
    parser.add_argument("--branch-runs", type=int, default=3, help="cli: full branches per run")
    args = parser.parse_args()

    builds = []
    for spec in args.builds:
        name, _, path = spec.partition("=")
        if not path or not os.access(path, os.X_OK):
            sys.exit(f"Error: {spec}: expected NAME=PATH to an executable")
        if not name or name in (n for n, _ in builds):
            sys.exit(f"Error: {spec}: every build needs a name of its own, or their samples would merge")
        builds.append((name, os.path.abspath(path)))
    if args.side == "proxy" and not args.cli:
        sys.exit("Error: proxy needs --cli=BIN")
    names = [n for n, _ in builds]
    # Taken before the first run, so a run that fails part way still says what it measured.
    measured = provenance(builds)

    samples, files = {}, []
    started = time.monotonic()
    try:
        collect(args, builds, samples, files)
    finally:
        # Whatever completed is kept, a run that failed part way included.
        if samples:
            save(args, builds, measured, names, samples, files, started)


def collect(args, builds, samples, files):
    for rnd in range(args.rounds):
        for name, path in order(builds, rnd):
            label = f"ab-{name}-r{rnd + 1}"
            if args.side == "proxy":
                for load in args.loads.split(","):
                    file, result = run([os.path.join(BENCH, "run.sh"), f"--load={load}", f"--proxy={path}",
                                        f"--cli={args.cli}", f"--label={label}"])
                    samples.setdefault(load, {}).setdefault(name, []).append(proxy_metrics(result))
                    files.append({"build": name, "round": rnd + 1, "series": load, "file": file})
            else:
                file, result = run([os.path.join(BENCH, "cli.sh"), f"--cli={path}", f"--from={args.source}",
                                    f"--runs={args.runs}", f"--branch-runs={args.branch_runs}",
                                    f"--label={label}"])
                samples.setdefault("cli", {}).setdefault(name, []).append(cli_metrics(result, path))
                files.append({"build": name, "round": rnd + 1, "series": "cli", "file": file})


def save(args, builds, measured, names, samples, files, started):
    # A build with fewer rounds than the others (a failed run) still gets its median.
    medians = summarize(samples)
    complete = [n for n in names if all(n in per_build for per_build in samples.values())]
    if complete and complete[0] == names[0]:
        print_table(medians, complete)
    os.makedirs(RESULTS, exist_ok=True)
    out = os.path.join(RESULTS, datetime.now().strftime("%Y%m%d-%H%M%S") + f"-{args.side}.json")
    with open(out, "w") as f:
        json.dump({"timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                   "side": args.side, "rounds": args.rounds,
                   "builds": dict(builds), "provenance": measured,
                   "minutes": round((time.monotonic() - started) / 60, 1),
                   "medians": medians, "samples": samples, "runs": files}, f, indent=2)
        f.write("\n")
    print(f"\nSaved to: {out}")


if __name__ == "__main__":
    main()
