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
"""
import argparse, json, os, re, statistics, subprocess, sys, time
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

    samples, files = {}, []
    started = time.monotonic()
    try:
        collect(args, builds, samples, files)
    finally:
        # Whatever completed is kept, a run that failed part way included.
        if samples:
            save(args, builds, names, samples, files, started)


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


def save(args, builds, names, samples, files, started):
    # A build with fewer rounds than the others (a failed run) still gets its median.
    medians = summarize(samples)
    complete = [n for n in names if all(n in per_build for per_build in samples.values())]
    if complete and complete[0] == names[0]:
        print_table(medians, complete)
    os.makedirs(RESULTS, exist_ok=True)
    out = os.path.join(RESULTS, datetime.now().strftime("%Y%m%d-%H%M%S") + f"-{args.side}.json")
    with open(out, "w") as f:
        json.dump({"timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                   "side": args.side, "rounds": args.rounds, "builds": dict(builds),
                   "minutes": round((time.monotonic() - started) / 60, 1),
                   "medians": medians, "samples": samples, "runs": files}, f, indent=2)
        f.write("\n")
    print(f"\nSaved to: {out}")


if __name__ == "__main__":
    main()
