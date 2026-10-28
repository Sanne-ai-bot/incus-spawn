#!/usr/bin/env bash
set -euo pipefail

# Fails unless each uber-jar takes the platform role of the OS it runs on, as a released jar must
# (#1800). Run on the jars release.yml uploads, and on every PR by uber-jar-smoke.
#
#  - Its Platform.class is the one this build compiled from common's sources, byte for byte, and
#    carries no mark of the macOS-role test build (scripts/macos-role-build.sh), nor does its
#    manifest.
#  - On Linux, the CLI keeps the Linux role when told it is on macOS (-Dos.name, JAVA_TOOL_OPTIONS):
#    its help lists no 'vm' command, which only the macOS role has.
#
# Usage:  scripts/check-platform-role.sh <cli-uber-jar> <proxy-uber-jar>

[ $# -eq 2 ] || { echo "usage: $0 <cli-uber-jar> <proxy-uber-jar>" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPILED="$ROOT/common/target/classes/dev/incusspawn/Platform.class"
MARK=isx-macos-role-test-build
[ -f "$COMPILED" ] || { echo "error: $COMPILED not found; build first" >&2; exit 2; }

failed=0
fail() { echo "::error::$*"; failed=1; }

shipped=$(mktemp)
trap 'rm -f "$shipped"' EXIT
for jar in "$@"; do
    unzip -p "$jar" dev/incusspawn/Platform.class > "$shipped"
    if ! cmp -s "$COMPILED" "$shipped"; then
        fail "$jar: dev/incusspawn/Platform.class is not the one compiled from common's sources"
    fi
    if grep -qaF "$MARK" "$shipped" || unzip -p "$jar" META-INF/MANIFEST.MF | grep -qiF "Isx-Test-Build"; then
        fail "$jar is the macOS-role test build, which must never be released"
    fi
done

if [ "$(uname -s)" = Linux ]; then
    cli=$1
    # The commands --help lists, one name per line, read whole before they are matched: an early
    # exit of grep -q would end the pipe with SIGPIPE, which pipefail reads as no match.
    commands() { "$@" --help 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | awk '/^    [a-z]/ { print $1 }'; }
    listed=$(commands java -jar "$cli")
    grep -qx proxy <<< "$listed" || fail "$cli --help listed no commands, so the checks below would prove nothing"
    listed=$(commands java -Dos.name="Mac OS X" -jar "$cli")
    grep -qx vm <<< "$listed" && fail "$cli takes macOS's role on Linux under -Dos.name"
    listed=$(commands env JAVA_TOOL_OPTIONS='-Dos.name=Mac' java -jar "$cli")
    grep -qx vm <<< "$listed" && fail "$cli takes macOS's role on Linux under JAVA_TOOL_OPTIONS"
fi

[ "$failed" -eq 0 ] && echo "Platform role checks passed for: $*"
exit "$failed"
