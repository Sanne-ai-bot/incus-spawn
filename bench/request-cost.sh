#!/bin/bash
# bench/request-cost.sh — What one Incus request costs on this host, outside isx
#
# Times plain HTTP requests on the Incus daemon's Unix socket: the local socket on Linux, the
# appliance's vsock-bridged socket on macOS (the one isx uses). No isx process is involved, so
# this is the transport and the daemon alone: the number to multiply a request count by (the
# budgets in InstanceLifecycleRequestBudgetTest, the count bench/trace-branch.sh prints) to
# see what those requests cost on a host. bench/cli.sh measures whole commands instead.
#
# Reads are timed on one kept-alive connection, and once more with a new connection per
# request. With --write=INSTANCE it also times a settings write (a PATCH setting one user.*
# key, removed afterwards) on that instance, which should be a throwaway one.
#
# Requires: python3, and access to the daemon's socket (on macOS: the VM, isx vm start).
#
# Usage:
#   bench/request-cost.sh                       # reads only
#   bench/request-cost.sh --instance=tpl-minimal  # the instance the single-instance read asks for
#   bench/request-cost.sh --write=isx-scratch   # also time a settings write on isx-scratch
#   bench/request-cost.sh --runs=300
set -euo pipefail

RUNS=300
INSTANCE=""
WRITE=""
SOCKET=""
while [ $# -gt 0 ]; do
    case "$1" in
        --runs=*) RUNS="${1#--runs=}" ;;
        --instance=*) INSTANCE="${1#--instance=}" ;;
        --write=*) WRITE="${1#--write=}" ;;
        --socket=*) SOCKET="${1#--socket=}" ;;
        --help|-h)
            echo "Usage: bench/request-cost.sh [--runs=N] [--instance=NAME] [--write=INSTANCE] [--socket=PATH]"
            echo "  --runs=N          Timed requests per line (default 300, after 20 warmups)"
            echo "  --instance=NAME   Instance for the single-instance read (default: --write's, else the first listed)"
            echo "  --write=INSTANCE  Also time a settings write on this instance (use a throwaway one)"
            echo "  --socket=PATH     The daemon's Unix socket (default: the appliance's on macOS, Incus's on Linux)"
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

die() { echo "Error: $*" >&2; exit 1; }
command -v python3 &>/dev/null || die "python3 not found on PATH"
[[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || die "--runs must be a positive integer, got '$RUNS'"

if [ -z "$SOCKET" ]; then
    if [ "$(uname -s)" = Darwin ]; then
        # Incus runs in the VM, behind the Unix socket isx itself uses (Environment.vmVsockSocket).
        SOCKET="$HOME/.local/state/incus-spawn/vm.incus.sock"
        [ -S "$SOCKET" ] || die "No appliance socket at $SOCKET. Is the VM running? (isx vm start)"
    else
        # The order isx tries them in (UnixSocketTransport.SOCKET_CANDIDATES)
        for candidate in /run/incus/unix.socket /var/lib/incus/unix.socket; do
            if [ -S "$candidate" ]; then SOCKET="$candidate"; break; fi
        done
        [ -n "$SOCKET" ] || die "No Incus socket found. Is Incus running? (or pass --socket=PATH)"
    fi
fi

export SOCKET RUNS INSTANCE WRITE
python3 - <<'PY'
import http.client, json, os, socket, statistics, sys, time

SOCKET, RUNS = os.environ["SOCKET"], int(os.environ["RUNS"])
WARMUPS = 20
# Read if the host has it: isx's bridge, which a plain Incus host need not have.
BRIDGE = "/1.0/networks/incusbr0"
OPTIONAL = {BRIDGE}

class UnixConnection(http.client.HTTPConnection):
    def __init__(self):
        super().__init__("incus", timeout=10)
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.settimeout(self.timeout)
        self.sock.connect(SOCKET)

def request(conn, method, path, body=None):
    conn.request(method, path, body=body, headers={"Content-Type": "application/json"} if body else {})
    response = conn.getresponse()
    data = response.read()  # to Content-Length: the connection stays usable
    if response.status == 404 and path in OPTIONAL:
        raise LookupError(path)
    if response.status >= 400:
        sys.exit(f"Error: {method} {path} answered {response.status}: {data[:200].decode(errors='replace')}")
    return data

def timed(method, path, body=None, runs=RUNS, keep_alive=True):
    """Milliseconds per request; with keep_alive=False each one also opens its connection."""
    conn = UnixConnection() if keep_alive else None
    samples = []
    for i in range(WARMUPS + runs):
        payload = body(i) if body else None
        start = time.perf_counter()
        if keep_alive:
            request(conn, method, path, payload)
        else:
            once = UnixConnection()
            request(once, method, path, payload)
            once.close()
        if i >= WARMUPS:
            samples.append((time.perf_counter() - start) * 1000)
    if conn:
        conn.close()
    return samples

def report(label, samples):
    ordered = sorted(samples)
    p90 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.9))]
    print(f"  {label:<48} {statistics.median(samples):6.2f} ms median  {p90:6.2f} p90  {ordered[0]:6.2f} min  ({len(samples)} runs)")

def main():
    conn = UnixConnection()
    server = json.loads(request(conn, "GET", "/1.0"))["metadata"]
    names = [url.rsplit("/", 1)[1] for url in json.loads(request(conn, "GET", "/1.0/instances"))["metadata"]]
    conn.close()
    instance = os.environ["INSTANCE"] or os.environ["WRITE"] or (names[0] if names else "")

    print(f"Incus {server['environment']['server_version']} on {SOCKET.replace(os.path.expanduser('~'), '~')}, {len(names)} instance(s)")
    print("Reads, one kept-alive connection:")
    report("GET /1.0", timed("GET", "/1.0"))
    if instance:
        report(f"GET /1.0/instances/{instance}", timed("GET", f"/1.0/instances/{instance}"))
    report("GET /1.0/instances?recursion=1", timed("GET", "/1.0/instances?recursion=1"))
    try:
        report(f"GET {BRIDGE}", timed("GET", BRIDGE))
    except LookupError:
        print(f"  GET {BRIDGE:<44} skipped: this host has no incusbr0")
    print("Reads, a new connection for each:")
    report("GET /1.0", timed("GET", "/1.0", keep_alive=False))
    if instance:
        report(f"GET /1.0/instances/{instance}", timed("GET", f"/1.0/instances/{instance}", keep_alive=False))

    target = os.environ["WRITE"]
    if target:
        key = "user.isx-bench-request-cost"
        path = f"/1.0/instances/{target}"
        print("Settings write, one kept-alive connection:")
        try:
            # Fewer runs: each one rewrites the instance's backup file.
            report(f"PATCH {path} (one config key)",
                   timed("PATCH", path, lambda i: json.dumps({"config": {key: str(i)}}), runs=min(RUNS, 50)))
        finally:
            # An empty value removes the key. request() exits on a refusal, so a key left
            # behind is not silent.
            request(UnixConnection(), "PATCH", path, json.dumps({"config": {key: ""}}))

try:
    main()
except OSError as e:  # the VM stopped mid-run, a timeout
    sys.exit(f"Error: cannot talk to the daemon on {SOCKET}: {e}")
PY
