#!/bin/bash
# Integration test: an airgapped branch has no network, and a branch of it gets it back.
#
# The default profile provides the NIC, and Incus cannot remove a profile device from one
# instance, only mask it with a `type: none` device of the same name. Airgap branches kept full
# network access for as long as isx removed the device instead (#813), which no fake daemon
# caught: it takes a real Incus to expand the profile's devices.
#
# A proxy device bound in the instance is a way out too (#1518): it listens in the guest and
# connects from the host. A profile of the test's own gives one to a copy of tpl-minimal that the
# branch is made from, and another after the branch, which isx must mask when it next starts the
# instance. Not the default profile: Incus refuses the change for every VM using it, since a VM
# takes only NAT proxies.
#
# Requires:
#   - tpl-minimal already built
#   - isx and incus on PATH, and the proxy running
#
# Usage:
#   sg incus-admin -c "bash .github/scripts/test-airgap.sh"

set -euo pipefail

AIRGAPPED=test-airgap
SOURCE=test-airgap-source
PROFILE=isx-test-airgap
RECONNECTED=test-airgap-reconnected

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

# NICs Incus attaches at start: the expanded view, since a profile's NIC is not in `devices`
nics() {
    incus query "/1.0/instances/$1" \
        | jq -r '.expanded_devices | to_entries[] | select(.value.type == "nic") | .key'
}

no_nics() { [ -z "$(nics "$1")" ]; }
device_type() { [ "$(incus query "/1.0/instances/$1" | jq -r ".expanded_devices[\"$2\"].type")" = "$3" ]; }
reaches_through_proxy() { incus exec "$1" -- curl -s -m 5 -o /dev/null "http://127.0.0.1:$2/"; }
cannot_reach_through_proxy() { ! reaches_through_proxy "$@"; }
has_nic() { [ -n "$(nics "$1")" ]; }
stamped() { [ "$(incus config get "$1" user.incus-spawn.network-mode)" = "$2" ]; }
only_loopback() { [ "$(incus exec "$1" -- ls /sys/class/net)" = "lo" ]; }
cannot_reach_internet() { ! incus exec "$1" -- curl -s -m 5 -o /dev/null https://1.1.1.1; }

wait_for_ipv4() {
    incus exec "$1" -- bash -c '
        systemctl start systemd-networkd 2>/dev/null
        for i in $(seq 1 30); do
            ip -4 -o addr show scope global | grep -q inet && exit 0
            sleep 0.5
        done
        exit 1'
}

cleanup() {
    incus delete -f "$AIRGAPPED" "$RECONNECTED" "$SOURCE" >/dev/null 2>&1 || true
    incus profile delete "$PROFILE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== Airgapped branch ==="
incus profile create "$PROFILE"
incus profile device add "$PROFILE" isx-test-fwd proxy bind=instance \
    listen=tcp:127.0.0.1:8080 connect=tcp:1.1.1.1:80
incus copy tpl-minimal "$SOURCE"
incus profile add "$SOURCE" "$PROFILE"
isx branch "$AIRGAPPED" --from "$SOURCE" --airgap --no-start
incus config show "$AIRGAPPED" --expanded
check "no NIC in expanded_devices" no_nics "$AIRGAPPED"
check "the profile's proxy bound in the instance is masked" device_type "$AIRGAPPED" isx-test-fwd none
check "stamped network-mode AIRGAP" stamped "$AIRGAPPED" AIRGAP
incus start "$AIRGAPPED"
check "no interface but loopback" only_loopback "$AIRGAPPED"
check "cannot reach the internet" cannot_reach_internet "$AIRGAPPED"
check "cannot reach out through the proxy" cannot_reach_through_proxy "$AIRGAPPED" 8080
incus stop -f "$AIRGAPPED"

echo "=== A proxy the profile gains after the branch ==="
incus profile device add "$PROFILE" isx-test-fwd2 proxy bind=instance \
    listen=tcp:127.0.0.1:8081 connect=tcp:1.1.1.1:80
# An unknown action fails, by design, after the same preparation every start for use runs
isx run "$AIRGAPPED" --action=no-such-action || true
check "masked when isx starts it" device_type "$AIRGAPPED" isx-test-fwd2 none
check "cannot reach out through it" cannot_reach_through_proxy "$AIRGAPPED" 8081
incus stop -f "$AIRGAPPED"

echo "=== Branch with network from the airgapped one ==="
isx branch "$RECONNECTED" --from "$AIRGAPPED" --no-start
check "NIC back in expanded_devices" has_nic "$RECONNECTED"
check "proxy back in expanded_devices" device_type "$RECONNECTED" isx-test-fwd proxy
check "the gained proxy back too" device_type "$RECONNECTED" isx-test-fwd2 proxy
check "network-mode stamp cleared" stamped "$RECONNECTED" ""
incus start "$RECONNECTED"
check "gets an IPv4 address" wait_for_ipv4 "$RECONNECTED"
# The positive control: without it, the "cannot reach out through" checks above would pass
# whenever curl fails for any other reason
check "reaches out through the proxy once it is back" reaches_through_proxy "$RECONNECTED" 8080
check "and through the one gained after the branch" reaches_through_proxy "$RECONNECTED" 8081

echo
echo "$((TESTS - FAIL))/$TESTS passed"
[ "$FAIL" -eq 0 ]
