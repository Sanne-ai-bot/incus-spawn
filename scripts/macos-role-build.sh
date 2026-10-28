#!/usr/bin/env bash
set -euo pipefail

# The macOS-role test build (#1800): copies of the uber-jars whose Platform says macOS, so CI can
# run isx and isx-proxy in their macOS roles on Linux (appliance/test-macos-role.sh). It is never
# released and publishes nothing.
#
# The released code has no switch for this. Platform takes its role from the OS it runs on, and on
# a Linux kernel nothing at run time changes it. Here the class itself is replaced: Platform.java
# is copied from common's sources with its one OS_NAME line pinned to macOS, compiled, and put in
# place of Platform.class in a copy of each jar. Everything else is the bytes the release build
# made. Each copy is marked, in Platform.class and in its manifest, and
# scripts/check-platform-role.sh, run on the uber-jars release.yml uploads, fails on either mark.
#
# Usage:  scripts/macos-role-build.sh <cli-uber-jar> <proxy-uber-jar> <out-dir>
#
# Writes <out-dir>/isx-macos-role-test.jar and <out-dir>/isx-proxy-macos-role-test.jar. The
# uber-jars come from 'mvn package -Prelease -DskipTests' (cli/target, proxy/target).

[ $# -eq 3 ] || { echo "usage: $0 <cli-uber-jar> <proxy-uber-jar> <out-dir>" >&2; exit 2; }
CLI_JAR=$1
PROXY_JAR=$2
OUT=$3
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/common/src/main/java/dev/incusspawn/Platform.java"
# The line the pin replaces; PlatformRoleBuildGuardTest fails if Platform.java no longer has it.
LINE='    private static final String OS_NAME = osName(System.getProperty("os.name"), Path.of("/proc/sys/kernel/ostype"), Path.of("/System/Library/CoreServices/SystemVersion.plist"));'
MARK=isx-macos-role-test-build

[ "$(grep -cxF "$LINE" "$SOURCE")" = 1 ] \
    || { echo "error: $SOURCE does not have the OS_NAME line this script pins" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src/dev/incusspawn" "$WORK/classes" "$OUT"
# A constant the class keeps in its constant pool, which is what the release check looks for.
awk -v line="$LINE" -v mark="$MARK" '
    $0 == line {
        print "    private static final String OS_NAME = \"mac os x\";"
        print "    public static final String TEST_BUILD = \"" mark "\";"
        next
    }
    { print }' "$SOURCE" > "$WORK/src/dev/incusspawn/Platform.java"
javac -nowarn --release 25 -cp "$CLI_JAR" -d "$WORK/classes" "$WORK/src/dev/incusspawn/Platform.java"
# A nested class would compile to a second file this does not swap, and a jar mixing the two.
[ "$(ls "$WORK/classes/dev/incusspawn")" = Platform.class ] \
    || { echo "error: Platform.java compiles to more than Platform.class; swap every class it makes" >&2; exit 1; }
printf 'Isx-Test-Build: %s\n' "$MARK" > "$WORK/manifest"

# pin <uber-jar> <name>: a copy of the jar, in OUT, with the pinned Platform.class.
pin() {
    unzip -l "$1" dev/incusspawn/Platform.class >/dev/null \
        || { echo "error: $1 has no dev/incusspawn/Platform.class" >&2; exit 1; }
    cp "$1" "$OUT/$2"
    jar --update --file "$OUT/$2" --manifest "$WORK/manifest" -C "$WORK/classes" dev/incusspawn/Platform.class
    echo "$OUT/$2"
}
pin "$CLI_JAR" isx-macos-role-test.jar
pin "$PROXY_JAR" isx-proxy-macos-role-test.jar
