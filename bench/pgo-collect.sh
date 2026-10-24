#!/bin/bash
# bench/pgo-collect.sh — Collect the PGO profiles the release binaries are optimized with
#
# Runs instrumented builds of isx-proxy and isx through the workloads docs/dev/PGO.md (#1636) lists:
# the proxy under the maven, saturate, anthropic and vertex loads of bench/run.sh, then the
# weighted CLI flows of bench/cli.sh with the proxy serving them. Every process writes one
# gzipped .iprof; the optimized build takes them all (--pgo=a,b,...), and their counts add up.
#
# Requires: what bench/run.sh and bench/cli.sh require (isx init, a running Incus, Podman for
#           Hyperfoil, git, ssh), Linux, and nothing serving the proxy's ports: stop the
#           service first (systemctl --user stop incus-spawn-proxy). Builds tpl-isx-bench when
#           it is missing.
#           The binaries: an instrumented build of both, from one commit, e.g.
#             mvn package -Dnative -DskipTests -Dnative.optimization=-O3,--pgo-instrument \
#               -Dquarkus.native.additional-build-args-append=-H:+ParseRuntimeOptions
#           (release builds turn runtime option parsing off; each process needs
#           -XX:ProfilesDumpFile to write a profile of its own).
#
# Usage:
#   bench/pgo-collect.sh --proxy=BIN --cli=BIN [--out=DIR]
#   (DIR defaults to bench/results/pgo; it gets proxy/ and cli/, emptied first)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROXY_BIN=""
CLI_BIN=""
OUT_DIR="$SCRIPT_DIR/results/pgo"

while [ $# -gt 0 ]; do
    case "$1" in
        --proxy=*) PROXY_BIN="${1#--proxy=}" ;;
        --proxy) shift; PROXY_BIN="${1:-}" ;;
        --cli=*) CLI_BIN="${1#--cli=}" ;;
        --cli) shift; CLI_BIN="${1:-}" ;;
        --out=*) OUT_DIR="${1#--out=}" ;;
        --out) shift; OUT_DIR="${1:-}" ;;
        --help|-h) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "Error: $*" >&2; exit 1; }
absolute() { echo "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; }

[ "$(uname -s)" = Linux ] || die "Linux only: the model loads and the CLI flows drive instances through the incus client"
[ -x "$PROXY_BIN" ] || die "--proxy=BIN: no executable instrumented proxy at '$PROXY_BIN'"
[ -x "$CLI_BIN" ] || die "--cli=BIN: no executable instrumented CLI at '$CLI_BIN'"
PROXY_BIN="$(absolute "$PROXY_BIN")"
CLI_BIN="$(absolute "$CLI_BIN")"
GATEWAY_IP=$(incus network get incusbr0 ipv4.address 2>/dev/null | cut -d/ -f1) || true
[ -n "$GATEWAY_IP" ] || die "Could not determine the Incus bridge gateway IP. Is Incus running?"
HEALTH_URL="http://$GATEWAY_IP:18080/health"
curl -sf --max-time 2 "$HEALTH_URL" &>/dev/null && die "Something is already serving $GATEWAY_IP:18080. Stop it first:
  systemctl --user stop incus-spawn-proxy"

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
rm -rf "$OUT_DIR/proxy" "$OUT_DIR/cli"
mkdir -p "$OUT_DIR/proxy" "$OUT_DIR/cli"
WORK_DIR="$(mktemp -d)"

PROXY_PID=""
stop_proxy() {
    [ -n "$PROXY_PID" ] || return 0
    kill "$PROXY_PID" 2>/dev/null && wait "$PROXY_PID" 2>/dev/null || true
    PROXY_PID=""
}
trap 'stop_proxy; rm -rf "$WORK_DIR"' EXIT

# A proxy for the steps that need one, writing its profile to $1.
start_proxy() {
    "$PROXY_BIN" -XX:ProfilesDumpFile="$1" --gateway-ip "$GATEWAY_IP" > "$WORK_DIR/proxy.log" 2>&1 &
    PROXY_PID=$!
    for _ in $(seq 1 60); do
        curl -sf "$HEALTH_URL" &>/dev/null && return 0
        kill -0 "$PROXY_PID" 2>/dev/null || die "The proxy failed to start: $(tail -5 "$WORK_DIR/proxy.log")"
        sleep 0.25
    done
    die "The proxy did not answer on $HEALTH_URL within 15s"
}

if ! incus info tpl-isx-bench &>/dev/null; then
    echo "=== Building tpl-isx-bench (profiles discarded) ==="
    start_proxy "$WORK_DIR/setup-proxy.iprof"
    (cd "$SCRIPT_DIR" && "$CLI_BIN" -XX:ProfilesDumpFile="$WORK_DIR/setup-cli.iprof" build tpl-isx-bench --yes) \
        || die "Could not build tpl-isx-bench"
    stop_proxy
fi

for load in maven saturate anthropic vertex; do
    echo ""
    echo "=== Proxy: --load=$load ==="
    "$SCRIPT_DIR/run.sh" --load="$load" --collect="$OUT_DIR/proxy" --proxy="$PROXY_BIN" --cli="$CLI_BIN"
done

# The CLI flows need a proxy of their build to answer; it profiles what they ask of it.
echo ""
echo "=== CLI flows ==="
start_proxy "$OUT_DIR/proxy/proxy-cli-flows.iprof"
"$SCRIPT_DIR/cli.sh" --collect="$OUT_DIR/cli" --cli="$CLI_BIN"
stop_proxy
[ -s "$OUT_DIR/proxy/proxy-cli-flows.iprof" ] || die "The proxy serving the CLI flows wrote no profile"
gzip -f "$OUT_DIR/proxy/proxy-cli-flows.iprof"

echo ""
echo "=== Profiles ==="
for side in proxy cli; do
    count=$(find "$OUT_DIR/$side" -name '*.iprof.gz' | wc -l)
    [ "$count" -gt 0 ] || die "No $side profiles in $OUT_DIR/$side"
    echo "  $side: $count files, $(du -sh "$OUT_DIR/$side" | cut -f1), in $OUT_DIR/$side"
done
echo ""
echo "Build with them (one comma-separated list per binary):"
echo "  -Dnative.optimization=-O3,--pgo=\$(ls $OUT_DIR/proxy/*.iprof.gz | paste -sd,)"
