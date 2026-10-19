#!/bin/bash
# Generate config.yaml fixtures with a released isx's own serializer.
#
# PreviousReleaseConfigCompatTest and the CI step test-previous-release-config.sh load
# these files to pin that what a released isx wrote keeps working. They must be what that
# release actually wrote -- empty strings, stray keys and all -- so they are produced by
# the release's own SpawnConfig.save(), never written by hand.
#
# Usage:  scripts/config-compat/generate-fixtures.sh v0.3.8
# Output: common/src/test/resources/config-compat/<tag>-*.yaml
#
# Add a fixture set for each release whose config format differs from the last one, then
# list its files in PreviousReleaseConfigCompatTest. GenerateConfigFixtures writes the set
# every release has; each other Generate*.java writes what only some releases do, and runs
# against a release that has what it calls (below).

set -euo pipefail

TAG="${1:?usage: $0 <release-tag>}"
REPO="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
OUT="$REPO/common/src/test/resources/config-compat"
WORK="$(mktemp -d)"
trap 'git -C "$REPO" worktree remove --force "$WORK/src" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

git -C "$REPO" worktree add --detach "$WORK/src" "$TAG" >/dev/null
(cd "$WORK/src" && mvn -q -pl common -am compile \
    && mvn -q -pl common dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt")

CONFIG="$WORK/src/common/src/main/java/dev/incusspawn/config"
GENERATORS=(GenerateConfigFixtures)
[[ -f "$CONFIG/NamespaceAccounts.java" ]] && GENERATORS+=(GenerateAccountConfigFixtures)
grep -q 'public void setMcp(' "$CONFIG/SpawnConfig.java" && GENERATORS+=(GenerateMcpSectionConfigFixtures)
[[ -f "$CONFIG/GitCacheConfig.java" ]] && GENERATORS+=(GenerateSectionsConfigFixtures)

CP="$WORK/src/common/target/classes:$(cat "$WORK/cp.txt")"
mkdir -p "$WORK/classes" "$OUT"
SOURCES=()
for generator in "${GENERATORS[@]}"; do SOURCES+=("$REPO/scripts/config-compat/$generator.java"); done
javac -d "$WORK/classes" -cp "$CP" "${SOURCES[@]}"
for generator in "${GENERATORS[@]}"; do
    java -cp "$WORK/classes:$CP" "$generator" "$OUT" "$TAG"
done

ls -1 "$OUT"/"$TAG"-*.yaml
