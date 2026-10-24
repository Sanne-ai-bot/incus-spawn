#!/bin/bash
# Integration test: the loopback SSH forwarder carries SSH to a real instance (#1600).
#
# The Gateway action starts `isx ssh-forward` only where the instance's address cannot be reached
# from the host, which today means macOS, so no Linux run ever uses it. The forwarder itself is
# platform-independent: here it is started by hand and an OpenSSH client logs in through it with
# isx's managed key, as Gateway does at 127.0.0.1:<port> on a Mac.
#
# Requires:
#   - tpl-test-sshd already built (tpl-minimal + sshd)
#   - isx and incus on PATH, and the proxy running
#
# Usage:
#   sg incus-admin -c "bash .github/scripts/test-ssh-forward.sh"

set -euo pipefail

NAME=test-ssh-forward
RECORD=~/.config/incus-spawn/ssh/forward/$NAME
KEY=~/.config/incus-spawn/ssh/id_ed25519
KNOWN_HOSTS=~/.config/incus-spawn/ssh/known_hosts

TESTS=0
FAIL=0

check() {
    local desc="$1"; shift
    TESTS=$((TESTS + 1))
    if "$@"; then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
    else
        printf '  \033[31mFAIL\033[0m  %s\n' "$desc"
        FAIL=$((FAIL + 1))
    fi
}

FORWARDER=
FIRST=$(mktemp)
EMPTY=$(mktemp)
cleanup() {
    [ -n "$FORWARDER" ] && kill "$FORWARDER" 2>/dev/null || true
    rm -f "$FIRST" "$EMPTY" "$EMPTY.err"
    incus delete -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

ssh_through() {
    ssh -p "$1" -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=10 \
        -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null agentuser@127.0.0.1 "${@:2}"
}
# As the managed Host block does once the forwarder pinned the key: verified, never prompted.
ssh_strict() {
    ssh -p "$1" -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=10 \
        -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$2" agentuser@127.0.0.1 true 2>/dev/null
}
# Refused for the key, not for any other failure: ssh says which.
refused() {
    ! ssh -p "$1" -i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=10 \
        -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$2" agentuser@127.0.0.1 true 2> "$EMPTY.err" \
        && grep -q 'Host key verification failed' "$EMPTY.err"
}
pinned() { grep -qx "\[127.0.0.1\]:$1 $2 isx-forward:$NAME" "$KNOWN_HOSTS"; }
unpinned() { ! grep -q " isx-forward:$NAME\$" "$KNOWN_HOSTS" 2>/dev/null; }
# A JVM's dual-stack socket shows its IPv4 address mapped: [::ffff:127.0.0.1]:<port>.
listens_on_loopback_only() {
    local local_addrs
    local_addrs=$(ss -Hltn "sport = :$1" | awk '{print $4}')
    [ -n "$local_addrs" ] && ! grep -Evx "(127\.0\.0\.1|\[::ffff:127\.0\.0\.1\]):$1" <<< "$local_addrs"
}
stamped() { [ "$(incus config get "$NAME" user.incus-spawn.ssh-forward-port)" = "$1" ]; }
# Killed, it stays a zombie of this shell until reaped, so a running state is what counts.
gone() { ! ps -o stat= -p "$1" | grep -qv '^Z' && [ ! -e "$RECORD" ]; }
wait_gone() { for _ in $(seq 1 15); do gone "$1" && return 0; sleep 0.2; done; gone "$1"; }

echo "=== SSH through the loopback forwarder ==="
isx branch "$NAME" --from tpl-test-sshd

isx ssh-forward "$NAME" &
FORWARDER=$!
for _ in $(seq 1 100); do [ -s "$RECORD" ] && break; sleep 0.1; done
read -r PID PORT < "$RECORD"

check "the record names the forwarder" [ "$PID" = "$FORWARDER" ]
check "it listens on loopback only" listens_on_loopback_only "$PORT"
check "an OpenSSH login through it runs a command" \
    [ "$(ssh_through "$PORT" 'echo "$USER@$(cat /proc/sys/kernel/hostname)"')" = "agentuser@$NAME" ]
ssh_through "$PORT" 'sleep 2; echo first' > "$FIRST" &
SLOW=$!
check "a second session while the first is open" [ "$(ssh_through "$PORT" 'echo second')" = second ]
wait "$SLOW"
check "and the first completes" [ "$(cat "$FIRST")" = first ]
check "its port is stamped on the instance" stamped "$PORT"

echo "=== The host key is pinned ==="
HOST_KEY=$(incus exec "$NAME" -- cat /etc/ssh/ssh_host_ed25519_key.pub | awk '{print $1" "$2}')
check "the instance's key is pinned for its port" pinned "$PORT" "$HOST_KEY"
check "a strict login verifies it" ssh_strict "$PORT" "$KNOWN_HOSTS"
check "and without the pinned key it is refused, not prompted" refused "$PORT" "$EMPTY"

echo "=== Destroy stops it ==="
isx destroy "$NAME" --skip-confirmation
check "isx destroy ends the forwarder and its record" wait_gone "$PID"
check "and removes its pinned key" unpinned
FORWARDER=

echo "$((TESTS - FAIL))/$TESTS passed"
[ "$FAIL" -eq 0 ]
