#!/bin/bash
# scripts/pgo-merge.sh — Merge one binary's PGO profiles into the single file a release build applies
#
# bench/pgo-collect.sh writes one profile per process (about a hundred for the CLI). native-image
# merges what it is given and can write the result back (-H:PGODumpLoadedIprofPath), so this
# builds the module once with every profile and keeps the merged one: <out>/<module>.iprof.gz,
# what -Dnative.pgo=<out> reads. The build's output is discarded; its log shows the profile's
# applicability and relevance (-H:+PGOPrintProfileQuality).
#
# Requires: Oracle GraalVM with native-image (--pgo is Oracle-only), run from the checkout the
#           profiles were collected with.
#
# Usage:
#   scripts/pgo-merge.sh <cli|proxy> <profiles dir> <out dir>
set -euo pipefail

die() { echo "Error: $*" >&2; exit 1; }

[ $# -eq 3 ] || die "usage: scripts/pgo-merge.sh <cli|proxy> <profiles dir> <out dir>"
MODULE=$1
case "$MODULE" in cli|proxy) ;; *) die "module must be cli or proxy, not '$MODULE'" ;; esac
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROFILES="$(cd "$2" && pwd)" || die "No profiles directory $2"
mkdir -p "$3"
OUT="$(cd "$3" && pwd)"

shopt -s nullglob
files=("$PROFILES"/*.iprof.gz "$PROFILES"/*.iprof)
[ ${#files[@]} -gt 0 ] || die "No .iprof or .iprof.gz in $PROFILES"
# One argument in native-image's comma-separated list: its own commas are escaped, and the
# backslash doubled because the value passes through application.properties.
joined=""
for f in "${files[@]}"; do
    case "$f" in *,*) die "A profile path holds a comma: $f" ;; esac
    joined+="${joined:+\\\\,}$f"
done

MERGED="$OUT/$MODULE.iprof"
rm -f "$MERGED" "$MERGED.gz"
echo "Merging ${#files[@]} $MODULE profiles..."
# -O3 is what the CLI builds at with a profile (the pgo profile in cli/pom.xml); the proxy's own.
mvn -q -f "$PROJECT_DIR/pom.xml" package -Dnative -DskipTests -pl "$MODULE" -am -Dnative.optimization=-O3 \
    "-Dnative.pgo.args=,--pgo=$joined,-H:+UnlockExperimentalVMOptions,-H:+PGOPrintProfileQuality,-H:PGODumpLoadedIprofPath=$MERGED,-H:-UnlockExperimentalVMOptions" \
    > "$OUT/$MODULE-merge.log" 2>&1 || die "The merging build failed; see $OUT/$MODULE-merge.log"
grep -F '[PGO]' "$OUT/$MODULE-merge.log" || true
[ -s "$MERGED" ] || die "The build wrote no merged profile at $MERGED"
gzip -9 "$MERGED"
echo "Merged: $MERGED.gz ($(wc -c < "$MERGED.gz" | tr -d ' ') bytes)"
