#!/bin/bash
# isx branch --mcp-client registers isx mcp in the instance's Claude Code, and
# a copy loses the registration on its first start (#1182).
#
# Runs on the host. tpl-test-mcp carries a stub `claude` (test-claude-stub)
# that records its calls, so the registration is observed without a Claude
# Code install. The endpoint check `isx branch --mcp-client` prints goes
# through the real proxy bridge and a real host `isx mcp`.
#
# Usage: bash .github/scripts/test-mcp-client.sh

COORD=mcp-coord-test
COPY=mcp-copy-test
PLAIN=mcp-plain-test
LOG=$(mktemp -d)

TESTS=0
PASS=0
FAIL=0
ERRORS=""

assert() {
    local desc="$1"; shift
    local output
    TESTS=$((TESTS + 1))
    if output=$("$@" 2>&1); then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
        PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s\n' "$desc"
        [ -n "$output" ] && printf '         %s\n' "$output" | head -5
        FAIL=$((FAIL + 1))
        ERRORS="${ERRORS}  - ${desc}\n"
    fi
}

assert_eq() {
    local desc="$1" expected="$2"; shift 2
    local actual
    actual=$("$@" 2>/dev/null)
    TESTS=$((TESTS + 1))
    if [ "$actual" = "$expected" ]; then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
        PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s  (expected: %s, got: %s)\n' "$desc" "$expected" "$actual"
        FAIL=$((FAIL + 1))
        ERRORS="${ERRORS}  - ${desc} (expected '${expected}', got '${actual}')\n"
    fi
}

cleanup() {
    for i in "$COORD" "$COPY" "$PLAIN"; do isx destroy "$i" >/dev/null 2>&1 || true; done
    rm -rf "$LOG"
}
trap cleanup EXIT

in_guest() { incus exec "$1" -- sh -c "$2"; }
calls() { in_guest "$1" 'cat /home/agentuser/claude-calls.log 2>/dev/null'; }
# Each call's user and command, without the entry it added
call_summary() { calls "$1" | cut -d' ' -f1-6 | paste -sd'|'; }
marker() { in_guest "$1" 'stat -c %U /var/lib/isx/mcp-client-registered 2>/dev/null || echo none'; }

echo "========================================"
echo " --mcp-client registration (#1182)"
echo "========================================"
echo ""

echo "[1] A coordinator is registered on its first start"
isx branch "$COORD" --from tpl-test-mcp --proxy-only --mcp-client --format=plain 2> "$LOG/coord" >/dev/null
cat "$LOG/coord"
assert "the branch reaches isx mcp from the guest" grep -qE "Checking isx mcp from $COORD.* \\(isx [^,]+, [1-9][0-9]* tools\\)" "$LOG/coord"
assert "and finds the server in its Claude Code config" bash -c "! grep -q 'has no .isx. server' '$LOG/coord'"
assert_eq "claude mcp ran as the agent, once each way" \
    "agentuser mcp remove --scope user isx|agentuser mcp add-json --scope user isx" \
    call_summary "$COORD"
assert "the entry is the proxy's endpoint with the headers helper" \
    in_guest "$COORD" 'grep -q "\"url\":\"https://mcp.isx.internal/mcp\"" /home/agentuser/.claude-stub-isx.json && grep -q "/run/isx/instance-secret" /home/agentuser/.claude-stub-isx.json'
assert_eq "root's marker records it" "root" marker "$COORD"
assert "the agent reads the secret the headers helper sends" \
    in_guest "$COORD" "su -s /bin/sh agentuser -c 'cat /run/isx/instance-secret' | grep -qE '^[0-9a-f]{64}\$'"

echo ""
echo "[2] Its next start changes nothing"
incus stop "$COORD"
# The next-use start, without a terminal: the action is refused after the start
isx run "$COORD" --action=no-such-action >/dev/null 2>&1
assert_eq "the start ran no Claude Code" \
    "agentuser mcp remove --scope user isx|agentuser mcp add-json --scope user isx" call_summary "$COORD"
assert_eq "the marker stays" "root" marker "$COORD"

echo ""
echo "[3] A copy loses it on its first start"
incus stop "$COORD"
isx branch "$COPY" --from "$COORD" --proxy-only --format=plain 2> "$LOG/copy" >/dev/null
assert "an ordinary branch does not check the endpoint" bash -c "! grep -q 'isx mcp' '$LOG/copy'"
assert_eq "the copy's first start removed it" \
    "agentuser mcp remove --scope user isx|agentuser mcp add-json --scope user isx|agentuser mcp remove --scope user isx" \
    call_summary "$COPY"
assert "the entry is gone" in_guest "$COPY" '! test -e /home/agentuser/.claude-stub-isx.json'
assert "and not in its Claude Code config" in_guest "$COPY" '! grep -qF "mcp.isx.internal/mcp" /home/agentuser/.claude.json'
assert_eq "and so is the marker" "none" marker "$COPY"

echo ""
echo "[4] An ordinary branch runs no Claude Code"
isx branch "$PLAIN" --from tpl-test-mcp --proxy-only --format=plain 2> "$LOG/plain" >/dev/null
assert_eq "no calls" "" calls "$PLAIN"
assert_eq "no marker" "none" marker "$PLAIN"

echo ""
echo "========================================"
printf " Results: %d passed, %d failed, %d total\n" "$PASS" "$FAIL" "$TESTS"
echo "========================================"
if [ "$FAIL" -gt 0 ]; then
    printf "\nFailed:\n%b" "$ERRORS"
    exit 1
fi
