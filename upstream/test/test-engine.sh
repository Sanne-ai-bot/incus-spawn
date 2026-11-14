#!/usr/bin/env bash
# The upstream engine on a fixture repository with every publisher faked (#2036): curl serves
# fixtures by URL (a sha256sums.asc signed with a throwaway key, kernel.org's releases.json, the
# JetBrains feed, .sha256 files, small tarballs and jasolo's jars), gh answers release (per
# repository, with asset digests), pull-request and issue queries from fixtures and records what it
# is asked to change. The github-asset source (#2206) is checked to list only semver releases with
# the asset, and to refuse a jar that does not hash to its digest, a download URL that is not the
# repository's release asset, and a missing, null or non-sha256 digest. Checks that validate holds each
# manifest rule; that check passes a pin the publisher confirms and fails one it does not; that plan
# prepares a bump commit as the app for a newer release of the pinned series, waits out cooling,
# opens an issue for another series, a judgement item or a pin the publisher no longer confirms,
# proposes nothing for an older release, and fails closed on a bad signature, a sum the publisher
# does not confirm or a link off the allowlist; that verify-trees passes exactly the engine's
# regeneration of an app PR and fails one with anything else in it, a tampered sum, a downgrade,
# another series or a judgement item, and a review item's the same way, passes a person's bump
# whose values are the publisher's and fails one whose are not, and passes a PR touching no item;
# that issues and track open each issue once. Needs bash, git, jq, yq, gpg, gpgv, curl faked, tar,
# perl.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'gpgconf --homedir "$WORK/gnupg" --kill all 2>/dev/null; rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
FIX="$WORK/fixtures"; mkdir -p "$FIX/http" "$FIX/releases" "$WORK/bin"
APP_LOGIN="isx-upstream[bot]"; APP_EMAIL="123+isx-upstream[bot]@users.noreply.github.com"
export APP_LOGIN APP_EMAIL GH_TOKEN=x

# ---- a throwaway kernel.org signer -----------------------------------------------------------------
export GNUPGHOME="$WORK/gnupg"; mkdir -m 700 "$GNUPGHOME"
GPG=(gpg --batch --quiet --pinentry-mode loopback --passphrase "")
"${GPG[@]}" --quick-gen-key signer@test.invalid ed25519 sign never 2>/dev/null
"${GPG[@]}" --quick-gen-key other@test.invalid ed25519 sign never 2>/dev/null
FPR="$(gpg --batch --with-colons --fingerprint signer@test.invalid | awk -F: '$1 == "fpr" { print $10; exit }')"
gpg --batch --armor --export signer@test.invalid > "$WORK/signer.asc"
sumof() { printf 'linux-%s' "$1" | sha256sum | cut -d' ' -f1; }
# sign_sums <signer> <versions...>: the signed sums file, one tarball per version.
sign_sums() {
    local who="$1"; shift
    for v in "$@"; do printf '%s  linux-%s.tar.xz\n' "$(sumof "$v")" "$v"; done \
        | "${GPG[@]}" --local-user "$who@test.invalid" --clearsign
}
http() { printf '%s' "$1" | tr '/?&=:' '_____'; }
serve() { cp "$2" "$FIX/http/$(http "$1")"; }
serve_text() { printf '%s' "$2" > "$FIX/http/$(http "$1")"; }
sign_sums signer 7.2.8 7.2.9 7.2.10 7.3.1 > "$WORK/sums.asc"
serve https://cdn.kernel.org/pub/linux/kernel/v7.x/sha256sums.asc "$WORK/sums.asc"
NOW="$(date -u +%s)"
releases_json() { jq -n --argjson a "$1" --argjson b "$2" '{releases: [
    {version: "7.3.1", iseol: false, released: {timestamp: $b}},
    {version: "7.2.10", iseol: false, released: {timestamp: $a}}]}'; }
releases_json "$((NOW - 3 * 86400))" "$((NOW - 86400))" > "$WORK/releases.json"
serve https://www.kernel.org/releases.json "$WORK/releases.json"

# ---- JetBrains ---------------------------------------------------------------------------------------
mk_tarball() { # <version> <arch suffix> <top dir>
    local d="$WORK/tar/$1$2"; mkdir -p "$d/$3"; echo "hi $1$2" > "$d/$3/f"
    tar -C "$d" -czf "$WORK/tar/idea-$1$2.tar.gz" "$3"
    local sum; sum="$(sha256sum "$WORK/tar/idea-$1$2.tar.gz" | cut -d' ' -f1)"
    serve "https://download.jetbrains.com/idea/idea-$1$2.tar.gz" "$WORK/tar/idea-$1$2.tar.gz"
    serve_text "https://download.jetbrains.com/idea/idea-$1$2.tar.gz.sha256" "$sum *idea-$1$2.tar.gz"
    echo "$sum"
}
mkdir -p "$WORK/tar"
S24X="$(mk_tarball 2026.2.4 "" idea-IU-252.1)"; S24A="$(mk_tarball 2026.2.4 -aarch64 idea-IU-252.1)"
S3X="$(mk_tarball 2026.3 "" idea-IU-263.7)"; S3A="$(mk_tarball 2026.3 -aarch64 idea-IU-263.7)"
jb_entry() { jq -n --arg v "$1" --arg d "$2" '{version: $v, date: $d, downloads: {
    linux: {link: ("https://download.jetbrains.com/idea/idea-" + $v + ".tar.gz"), checksumLink: ("https://download.jetbrains.com/idea/idea-" + $v + ".tar.gz.sha256")},
    linuxARM64: {link: ("https://download.jetbrains.com/idea/idea-" + $v + "-aarch64.tar.gz"), checksumLink: ("https://download.jetbrains.com/idea/idea-" + $v + "-aarch64.tar.gz.sha256")}}}'; }
JB_URL='https://data.services.jetbrains.com/products/releases?code=IIU&type=release'
jq -n --argjson a "$(jb_entry 2026.3 "$(date -u -d "@$((NOW - 10 * 86400))" +%F)")" --argjson b "$(jb_entry 2026.2.4 2026-10-01)" '{IIU: [$a, $b]}' > "$WORK/jb.json"
serve "$JB_URL" "$WORK/jb.json"

# ---- a GitHub release asset (jasolo) -------------------------------------------------------------------
# The built-in pins 0.2.0; 0.2.1 is the newest of its series and cooled, 0.3.0 another series (an
# issue). The draft, the prerelease, the non-semver tag and 0.2.3, which lacks the jar, are not listed.
JS_DL=https://github.com/mkouba/jasolo/releases/download
jar_sum() { printf 'jasolo-%s' "$1" | sha256sum | cut -d' ' -f1; }
for v in 0.1.0 0.2.0 0.2.1 0.3.0; do printf 'jasolo-%s' "$v" > "$WORK/jasolo-$v.jar"; serve "$JS_DL/$v/jasolo-$v-runner.jar" "$WORK/jasolo-$v.jar"; done
js_release() { # <tag> <published_at seconds ago> [draft] [prerelease]: one release, jar and binary
    jq -n --arg t "$1" --arg at "$(date -u -d "@$((NOW - $2))" +%FT%TZ)" --argjson d "${3:-false}" --argjson p "${4:-false}" \
        --arg dl "$JS_DL" --arg sum "$(jar_sum "$1")" '{tag_name: $t, draft: $d, prerelease: $p, published_at: $at, assets: [
        {name: ("jasolo-" + $t + "-linux-x86_64"), state: "uploaded", digest: ("sha256:" + ("0" * 64)), browser_download_url: ($dl + "/" + $t + "/jasolo-" + $t + "-linux-x86_64")},
        {name: ("jasolo-" + $t + "-runner.jar"), state: "uploaded", digest: ("sha256:" + $sum), browser_download_url: ($dl + "/" + $t + "/jasolo-" + $t + "-runner.jar")}]}'
}
{
    js_release 0.3.0 86400; js_release 0.4.0-rc1 86400 false true; js_release 0.2.2 86400 true
    js_release 0.2.3 86400 | jq '.assets |= map(select(.name | endswith(".jar") | not))'
    js_release nightly 86400; js_release 0.2.1 $((3 * 86400)); js_release 0.2.0 $((10 * 86400)); js_release 0.1.0 $((20 * 86400))
} | jq -s . > "$WORK/js.json"
cp "$WORK/js.json" "$FIX/gh-releases-mkouba_jasolo.json"
# js_edit <jq filter on the 0.2.1 release>: the feed with that release changed.
js_edit() { jq "map(if .tag_name == \"0.2.1\" then $1 else . end)" "$WORK/js.json" > "$FIX/gh-releases-mkouba_jasolo.json"; }

# ---- the images releases -----------------------------------------------------------------------------
img_sum() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
mk_release() { # <tag>: its assets are named for its distribution and release, fedora-45
    local named; [[ "$1" =~ ^[a-z]+-[0-9]+ ]] && named="${BASH_REMATCH[0]}"
    mkdir -p "$FIX/releases/$1"
    for a in x86_64 aarch64; do
        printf '%s  %s-%s.tar.xz\n' "$(img_sum "$1-$a")" "$named" "$a"
        printf '%s  %s-%s-vm-direct.tar.xz\n' "$(img_sum "$1-$a-vm")" "$named" "$a"
    done > "$FIX/releases/$1/SHA256SUMS.v2"
}
# The built-in is on fedora-45-v1, the one typed tag (#1994); fedora-45-20261111 is the newest of its
# line, fedora-46 another Fedora, fedora-45-next a channel, which is never followed, and the draft is
# not published yet.
mk_release fedora-45-v1; mk_release fedora-45-20261111; mk_release fedora-46-20270101
jq -n --arg now "$(date -u -d "@$NOW" +%FT%TZ)" '[
  {tag_name: "fedora-46-20270101", draft: false, prerelease: false, published_at: $now},
  {tag_name: "fedora-45-next-20261125", draft: false, prerelease: false, published_at: $now},
  {tag_name: "fedora-45-20261111", draft: false, prerelease: false, published_at: $now},
  {tag_name: "fedora-45-v1", draft: false, prerelease: false, published_at: "2026-11-04T00:00:00Z"},
  {tag_name: "fedora-45-20261120", draft: true, prerelease: false, published_at: $now}]' > "$FIX/gh-releases.json"
echo '[]' > "$FIX/issues.json"

# ---- fakes -------------------------------------------------------------------------------------------
cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
url=""; out=""
while [ $# -gt 0 ]; do case "$1" in -o) out="$2"; shift ;; -*) ;; *) url="$1" ;; esac; shift; done
f="$FIX/http/$(printf '%s' "$url" | tr '/?&=:' '_____')"
[ -f "$f" ] || { echo "curl: (22) no fixture for $url" >&2; exit 22; }
cp "$f" "$out"
FAKE
cat > "$WORK/bin/gh" <<'FAKE'
#!/bin/bash
echo "gh $(printf "%s" "$*" | tr "\n" " ")" >> "$CALLS"
filter=""; args=(); for a in "$@"; do args+=("$a"); done
jqf() { local i; for ((i=0; i<${#args[@]}; i++)); do [ "${args[i]}" = --jq ] && echo "${args[i+1]}"; done; }
# Releases by repository: $FIX/gh-releases-<owner>_<name>.json, else the images repo's.
rels() { local r="${1#repos/}"; r="${r%%/releases*}"; r="$FIX/gh-releases-${r//\//_}.json"; [ -f "$r" ] && echo "$r" || echo "$FIX/gh-releases.json"; }
case "$1 $2" in
    "auth setup-git") exit 0 ;;
    "api --paginate") [[ "$3" == repos/*/releases ]] && jq -r "$(jqf)" "$(rels "$3")" ;;
    "api repos/"*)
        case "$2" in
            */releases/tags/*) tag="${2##*/}"; jq -r --arg t "$tag" '[.[] | select(.tag_name == $t)][0]' "$(rels "$2")" | jq -r "$(jqf)" ;;
            */pulls/*) cat "$FIX/pr-${2##*/}.json" ;;
            */compare/*) echo "deadbeef 0" ;;
        esac ;;
    "release download")
        tag="$3"; dir=""; for ((i=0; i<${#args[@]}; i++)); do [ "${args[i]}" = -D ] && dir="${args[i+1]}"; done
        ls "$FIX/releases/$tag"/SHA256SUMS* > /dev/null 2>&1 || { echo "release not found" >&2; exit 1; }
        cp "$FIX/releases/$tag"/SHA256SUMS* "$dir/" ;;
    "issue list") cat "$FIX/issues.json" ;;
    "pr list")
        state=open; for ((i=0; i<${#args[@]}; i++)); do [ "${args[i]}" = --state ] && state="${args[i+1]}"; done
        [ "$state" = closed ] && f="$FIX/prs-closed.json" || f="$FIX/prs.json"
        cat "$f" 2>/dev/null || echo '[]' ;;
    "issue create")
        title=""; for ((i=0; i<${#args[@]}; i++)); do [ "${args[i]}" = --title ] && title="${args[i+1]}"; done
        n=$(( $(jq length "$FIX/issues.json") + 1 ))
        jq --arg t "$title" --argjson n "$n" '. + [{number: $n, title: $t}]' "$FIX/issues.json" > "$FIX/issues.tmp" && mv "$FIX/issues.tmp" "$FIX/issues.json"
        echo "https://github.com/x/y/issues/$n" ;;
    "issue close"|"issue comment") ;;
    *) echo "gh: unexpected: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$WORK/bin/curl" "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH" FIX CALLS="$WORK/calls"; : > "$CALLS"

# ---- the fixture repository --------------------------------------------------------------------------
REPO="$WORK/repo"
mkdir -p "$REPO/upstream" "$REPO/appliance/kernel" "$REPO/.github/scripts" "$REPO/common/src/main/resources/images" "$REPO/common/src/main/resources/tools"
cp -r "$SRC/upstream/engine" "$REPO/upstream/engine"
cp "$SRC/.github/scripts/base-image-sums.sh" "$SRC/.github/scripts/verify-base-image-sums.sh" "$REPO/.github/scripts/"
sed "s/^AUTOSIGNER_FPR=\"[0-9A-F]*\"/AUTOSIGNER_FPR=\"$FPR\"/" "$SRC/appliance/kernel/update-kernel-pin.sh" > "$REPO/appliance/kernel/update-kernel-pin.sh"
chmod +x "$REPO/appliance/kernel/update-kernel-pin.sh"
cp "$WORK/signer.asc" "$REPO/appliance/kernel/kernel.org-autosigner.asc"
kernel_file() { printf '#!/bin/bash\n# fixture\nKERNEL_VERSION="%s"\nKERNEL_SHA256="%s"\necho build\n' "$1" "$2"; }
kernel_file 7.2.9 "$(sumof 7.2.9)" > "$REPO/appliance/kernel/build-kernel.sh"
image_file() { # <tag> <x86 rootfs> <arm rootfs> <x86 vm> <arm vm>
    cat <<Y
name: tpl-minimal
image: fedora-45-base
image_url: https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-45-{arch}.tar.xz
vm_image_url: https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-45-{arch}-vm-direct.tar.xz
image_tag: $1
image_sha256:
  x86_64: $2
  aarch64: $3
vm_image_sha256:
  x86_64: $4
  aarch64: $5
Y
}
image_file fedora-45-v1 "$(img_sum fedora-45-v1-x86_64)" "$(img_sum fedora-45-v1-aarch64)" "$(img_sum fedora-45-v1-x86_64-vm)" "$(img_sum fedora-45-v1-aarch64-vm)" > "$REPO/common/src/main/resources/images/minimal.yaml"
idea_file() { # <version> <x86 sum> <arm sum>
    cat <<Y
name: idea-backend
description: JetBrains IntelliJ IDEA Remote Development backend $1
downloads:
  - url: https://download.jetbrains.com/idea/idea-$1.tar.gz
    sha256: $2
    extract: /opt
    arch: x86_64
  - url: https://download.jetbrains.com/idea/idea-$1-aarch64.tar.gz
    sha256: $3
    extract: /opt
    arch: aarch64
run:
  - ln -snf /opt/idea-IU-* /opt/idea
Y
}
idea_file 2026.2.4 "$S24X" "$S24A" > "$REPO/common/src/main/resources/tools/idea-backend.yaml"
# The real tool file, moved to 0.2.0 with the fixture jar's sum, so the engine reads the shape it ships
# whatever version main pins.
JASOLO_YAML=common/src/main/resources/tools/jasolo.yaml
js_pin="$(sed -n 's|^  - url: https://github.com/mkouba/jasolo/releases/download/\([0-9.]*\)/.*|\1|p' "$SRC/$JASOLO_YAML")"
[ -n "$js_pin" ] || fail "cannot read the version $JASOLO_YAML pins"
sed "s/${js_pin//./\\.}/0.2.0/g; s/^    sha256: [0-9a-f]*\$/    sha256: $(jar_sum 0.2.0)/" "$SRC/$JASOLO_YAML" > "$REPO/$JASOLO_YAML"
grep -q 'download/0.2.0/jasolo-0.2.0-runner.jar$' "$REPO/$JASOLO_YAML" || fail "the jasolo fixture does not pin 0.2.0"
sed "s/fingerprint: [0-9A-F]*/fingerprint: $FPR/" "$SRC/upstream/manifest.yaml" > "$REPO/upstream/manifest.yaml"
cd "$REPO" && git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm fixture
git init -q --bare "$WORK/remote.git" && git remote add origin "$WORK/remote.git" && git push -q origin main
git --git-dir="$WORK/remote.git" config uploadpack.allowAnySHA1InWant true
E="$REPO/upstream/engine/upstream.sh"
BASE="$(git rev-parse HEAD)"
# commit_from <start> <message> <edit command...>: a commit on top of <start> with the edit.
commit_from() {
    local start="$1" msg="$2"; shift 2
    local wt="$WORK/wt"; rm -rf "$wt"; git -C "$REPO" worktree prune; git -C "$REPO" worktree add -q --detach "$wt" "$start"
    ( cd "$wt" && "$@" && git add -A && git -c user.name=a -c user.email="$APP_EMAIL" commit -qm "$msg" && git rev-parse HEAD )
}

# ---- validate ----------------------------------------------------------------------------------------
"$E" validate > /dev/null || fail "validate refuses the fixture manifest"
bad_manifest() { # <yq edit> <what it must fail for>
    local m="$WORK/bad.yaml"; yq "$1" "$REPO/upstream/manifest.yaml" > "$m"
    MANIFEST="$m" "$E" validate > /dev/null 2>&1 && fail "validate accepted a manifest with $2"
    return 0
}
bad_manifest '.items.kernel.skip-verify = true' "an unknown key"
bad_manifest 'del(.items.kernel.verify)' "a mechanical item without verify"
bad_manifest '.items.kernel.cooling = "1h"' "cooling under the 24h floor on a third-party source"
bad_manifest '.items.kernel.verify.fingerprint = "0000000000000000000000000000000000000000"' "a fingerprint that is not the pinned one"
bad_manifest '.items.kernel.class = "automatic"' "an unknown class"
bad_manifest '.items.kernel.verify.type = "publisher-sums"' "a verify.type the source does not provide"
bad_manifest 'del(.items.kernel.within)' "a mechanical item without within"
bad_manifest '.items.kernel.fields = ["KERNEL_VERSION"]' "fields that drift from the source"
bad_manifest '.items.kernel.files = ["appliance/kernel/nope.sh"]' "a file that does not exist"
bad_manifest '.items.kernel.class = "judgement" | del(.items.kernel.verify) | del(.items.kernel.source)' "a judgement item without a source, which plan asks for its releases"
bad_manifest 'del(.items.idea-backend.source.code)' "a JetBrains item without a product code"
bad_manifest '.items.idea-backend.source.url-prefix = "https://evil.example/idea/"' "a JetBrains prefix off download.jetbrains.com"
bad_manifest '.items.jasolo.source.repo = "jasolo"' "a GitHub asset item whose repo is not owner/name"
bad_manifest '.items.jasolo.source.asset = "jasolo-runner.jar"' "a GitHub asset name without {version}"
bad_manifest '.items.jasolo.source.asset = "../{version}/x.jar"' "a GitHub asset name that is a path"
bad_manifest '.items.jasolo.verify.type = "publisher-sums"' "a GitHub asset item claiming the publisher's own sums"
bad_manifest '.items.jasolo.cooling = "12h"' "a GitHub asset item cooling under the 24h floor"
yq '.items.kernel.class = "judgement" | del(.items.kernel.verify)' "$REPO/upstream/manifest.yaml" > "$WORK/judgement.yaml"
MANIFEST="$WORK/judgement.yaml" "$E" validate > /dev/null || fail "a judgement item needs no verify"
# A review item (#2210) is proposed as a PR too, so it is specified as fully as a mechanical one.
yq '.items.idea-backend.class = "review"' "$REPO/upstream/manifest.yaml" > "$WORK/review.yaml"
MANIFEST="$WORK/review.yaml" "$E" validate > /dev/null || fail "validate refuses a review item"
bad_manifest '.items.idea-backend.class = "review" | del(.items.idea-backend.verify)' "a review item without verify"
bad_manifest '.items.idea-backend.class = "review" | del(.items.idea-backend.within)' "a review item without within"
bad_manifest '.items.idea-backend.class = "review" | .items.idea-backend.cooling = "1h"' "a review item's cooling under the 24h floor"
echo "validate: ok"

# ---- check -------------------------------------------------------------------------------------------
for i in kernel base-image idea-backend jasolo; do "$E" check "$i" > /dev/null || fail "check $i fails on a pin the publisher confirms"; done
T="$WORK/tampered"; rm -rf "$T"; git -C "$REPO" worktree add -q "$T" HEAD
sed -i 's/^KERNEL_SHA256="./KERNEL_SHA256="f/' "$T/appliance/kernel/build-kernel.sh"
"$E" check kernel "$T" > /dev/null 2>&1 && fail "check kernel passed a sum the signed file does not give"
git -C "$REPO" worktree remove --force "$T"
echo "check: ok"

# ---- plan --------------------------------------------------------------------------------------------
plan() { local out="$WORK/plan-$1"; rm -rf "$out"; "$E" plan "$out" > "$out.log" 2>&1 || { cat "$out.log" >&2; fail "plan $1 failed"; }; echo "$out"; }
action() { awk -F'\t' -v i="$2" '$1 == i { print $2 }' "$1/plan.tsv"; }
version() { awk -F'\t' -v i="$2" '$1 == i { print $3 }' "$1/plan.tsv"; }
P="$(plan one)"
[ "$(action "$P" kernel)" = pr ] && [ "$(version "$P" kernel)" = 7.2.10 ] || fail "kernel: expected a PR for 7.2.10: $(cat "$P/plan.tsv")"
[ "$(action "$P" base-image)" = pr ] && [ "$(version "$P" base-image)" = fedora-45-20261111 ] || fail "base-image: expected a PR for fedora-45-20261111: $(cat "$P/plan.tsv")"
[ "$(action "$P" idea-backend)" = pr ] && [ "$(version "$P" idea-backend)" = 2026.3 ] || fail "idea-backend: expected a PR for 2026.3: $(cat "$P/plan.tsv")"
ls "$P/issues/" | grep -q '^kernel-7.3.json$' || fail "no issue for the 7.3 series: $(ls "$P/issues")"
ls "$P/issues/" | grep -q '^base-image-fedora-46.json$' || fail "no issue for the fedora-46 line: $(ls "$P/issues")"
[ "$(jq -r '.labels[0]' "$P/issues/kernel-7.3.json")" = go:design ] || fail "the kernel's series issue is not go:design"
[ "$(jq -r '.labels[0]' "$P/issues/base-image-fedora-46.json")" = go:build ] || fail "the base image's line issue is not go:build"
[ "$(action "$P" jasolo)" = pr ] && [ "$(version "$P" jasolo)" = 0.2.1 ] || fail "jasolo: expected a PR for 0.2.1: $(cat "$P/plan.tsv")"
[ "$(jq -r '.labels[0]' "$P/issues/jasolo-0.3.json" 2>/dev/null)" = go:build ] || fail "no go:build issue for jasolo's 0.3 series: $(ls "$P/issues")"
for i in kernel base-image idea-backend jasolo; do
    c="$(jq -r .commit "$P/$i.json")"
    [ "$(git -C "$REPO" log -1 --format=%ae "$c")" = "$APP_EMAIL" ] || fail "$i: the bump is not committed as the app"
    [ "$(git -C "$REPO" diff --name-only "$BASE" "$c")" = "$(yq -r ".items[\"$i\"].files[0]" "$REPO/upstream/manifest.yaml")" ] || fail "$i: the bump touches more than the item's file"
    grep -qF "<!-- upstream-watch: $i $(version "$P" "$i") -->" <<<"$(jq -r .body "$P/$i.json")" || fail "$i: the PR body has no marker"
done
[ "$(git -C "$REPO" show "$(jq -r .commit "$P/kernel.json"):appliance/kernel/build-kernel.sh" | grep -c "KERNEL_SHA256=\"$(sumof 7.2.10)\"")" = 1 ] || fail "the kernel bump has the wrong sum"
grep -q "idea-IU" "$P/idea-backend.json" || true
[ "$(git -C "$REPO" show "$(jq -r .commit "$P/idea-backend.json"):common/src/main/resources/tools/idea-backend.yaml" | grep -c "$S3A")" = 1 ] || fail "the IDEA bump has the wrong aarch64 sum"
echo "plan: ok"

# ---- the github-asset source (jasolo) ----------------------------------------------------------------
[ "$("$E" current jasolo)" = 0.2.0 ] || fail "jasolo: current is not 0.2.0: $("$E" current jasolo)"
[ "$("$E" releases jasolo | tr '\n' ' ')" = "0.1.0 0.2.0 0.2.1 0.3.0 " ] \
    || fail "jasolo: a draft, a prerelease, a non-semver tag or a release without the jar was listed: $("$E" releases jasolo | tr '\n' ' ')"
# The bump rewrites the URL, the sum and the description's version, and nothing else.
J="$(jq -r .commit "$P/jasolo.json")"
git -C "$REPO" show "$J:$JASOLO_YAML" > "$WORK/jasolo-bumped.yaml"
[ "$(diff "$REPO/$JASOLO_YAML" "$WORK/jasolo-bumped.yaml" | grep -c '^[<>]')" = 6 ] || fail "jasolo: the bump changes more than three lines: $(diff "$REPO/$JASOLO_YAML" "$WORK/jasolo-bumped.yaml")"
J_DIR="$(mktemp -d)"; mkdir -p "$J_DIR/$(dirname "$JASOLO_YAML")"; cp "$WORK/jasolo-bumped.yaml" "$J_DIR/$JASOLO_YAML"
TREE="$J_DIR" ITEM=jasolo bash -c '. "$1/lib.sh"; load_source jasolo; src_fields' _ "$REPO/upstream/engine" > "$WORK/jasolo-fields" \
    || fail "jasolo: cannot read the bumped fields"
printf 'description=jasolo 0.2.1, an MCP server that reads the Java sources of dependencies\nurl=%s\nsha256=%s\n' \
    "$JS_DL/0.2.1/jasolo-0.2.1-runner.jar" "$(jar_sum 0.2.1)" | diff - "$WORK/jasolo-fields" > /dev/null \
    || fail "jasolo: the bumped fields are not 0.2.1's: $(cat "$WORK/jasolo-fields")"
rm -rf "$J_DIR"
# jasolo_refused <what> <log pattern>: plan of jasolo alone fails, for that reason.
jasolo_refused() {
    UPSTREAM_ONLY=jasolo "$E" plan "$WORK/plan-js-$RANDOM" > "$WORK/js.log" 2>&1 && fail "jasolo: $1 was accepted"
    grep -q -- "$2" "$WORK/js.log" || fail "jasolo: $1 was not what failed: $(tail -3 "$WORK/js.log")"
    cp "$WORK/js.json" "$FIX/gh-releases-mkouba_jasolo.json"
}
# A jar that does not hash to GitHub's digest, re-hashed.
serve "$JS_DL/0.2.1/jasolo-0.2.1-runner.jar" "$WORK/jasolo-0.3.0.jar"
jasolo_refused "a jar that does not hash to its digest" "but GitHub's digest says"
serve "$JS_DL/0.2.1/jasolo-0.2.1-runner.jar" "$WORK/jasolo-0.2.1.jar"
# A download URL that leaves the repository, or names another asset, whatever the API says.
js_edit '.assets |= map(if (.name | endswith(".jar")) then .browser_download_url = "https://github.com/evil/jasolo/releases/download/0.2.1/jasolo-0.2.1-runner.jar" else . end)'
jasolo_refused "a download URL in another repository" "evil/jasolo"
js_edit '.assets |= map(if (.name | endswith(".jar")) then .browser_download_url = "https://objects.example/jasolo-0.2.1-runner.jar" else . end)'
jasolo_refused "a download URL off github.com" "objects.example"
# A digest that is missing, null or not a sha256.
js_edit '.assets |= map(if (.name | endswith(".jar")) then del(.digest) else . end)'
jasolo_refused "an asset without a digest" "has no sha256 digest"
js_edit '.assets |= map(if (.name | endswith(".jar")) then .digest = null else . end)'
jasolo_refused "an asset whose digest is null" "has no sha256 digest"
js_edit '.assets |= map(if (.name | endswith(".jar")) then .digest = "sha1:0123456789012345678901234567890123456789" else . end)'
jasolo_refused "a sha1 digest" "has no sha256 digest"
js_edit '.assets |= map(if (.name | endswith(".jar")) then .state = "starter" else . end)'
jasolo_refused "an asset still uploading" "is not uploaded"
# Without the re-hash (check, a carried PR) the digest alone is taken, so a fresh plan is still exact.
serve "$JS_DL/0.2.1/jasolo-0.2.1-runner.jar" "$WORK/jasolo-0.3.0.jar"
J_DIR="$WORK/js-norehash"; rm -rf "$J_DIR"; git -C "$REPO" worktree add -q --detach "$J_DIR" "$BASE"
UPSTREAM_REHASH=0 "$E" apply jasolo 0.2.1 "$J_DIR" > /dev/null || fail "jasolo: apply without the re-hash failed"
diff "$J_DIR/$JASOLO_YAML" "$WORK/jasolo-bumped.yaml" > /dev/null || fail "jasolo: apply without the re-hash wrote another file than plan"
git -C "$REPO" worktree remove --force "$J_DIR"
serve "$JS_DL/0.2.1/jasolo-0.2.1-runner.jar" "$WORK/jasolo-0.2.1.jar"
# A cooling period not over waits; a pinned release whose digest changed at GitHub is an issue.
jq 'map(if .tag_name == "0.2.1" then .published_at = (now | todate) else . end)' "$WORK/js.json" > "$FIX/gh-releases-mkouba_jasolo.json"
UPSTREAM_ONLY=jasolo "$E" plan "$WORK/plan-js-cool" > /dev/null 2>&1 || fail "jasolo: plan failed on a fresh release"
[ "$(action "$WORK/plan-js-cool" jasolo)" = wait ] || fail "jasolo: a fresh 0.2.1 did not wait: $(cat "$WORK/plan-js-cool/plan.tsv")"
jq --arg s "sha256:$(jar_sum other)" 'map(if .tag_name == "0.2.0" then .assets |= map(if (.name | endswith(".jar")) then .digest = $s else . end) else . end)' \
    "$WORK/js.json" > "$FIX/gh-releases-mkouba_jasolo.json"
UPSTREAM_ONLY=jasolo "$E" plan "$WORK/plan-js-pin" > /dev/null 2>&1 || fail "jasolo: plan failed on a republished pin"
[ "$(action "$WORK/plan-js-pin" jasolo)" = issue ] && [ -f "$WORK/plan-js-pin/issues/jasolo-pin-mismatch.json" ] \
    || fail "jasolo: a changed digest for the pinned release did not become an issue: $(cat "$WORK/plan-js-pin/plan.tsv")"
cp "$WORK/js.json" "$FIX/gh-releases-mkouba_jasolo.json"
echo "github-asset: ok"

# An open PR of ours that already carries the version is not re-hashed: no tarball is fetched.
jq -n '[{body: "x\n\n<!-- upstream-watch: idea-backend 2026.3 -->", headRepositoryOwner: {login: "Sanne"}, isCrossRepository: false}]' > "$FIX/prs.json"
mv "$FIX/http/$(http https://download.jetbrains.com/idea/idea-2026.3.tar.gz)" "$WORK/hidden.tgz"
P="$(plan carried)"
[ "$(action "$P" idea-backend)" = pr ] || fail "a carried version was not prepared without the tarball: $(cat "$P/plan.tsv")"
mv "$WORK/hidden.tgz" "$FIX/http/$(http https://download.jetbrains.com/idea/idea-2026.3.tar.gz)"; rm -f "$FIX/prs.json"
# Cooling: a release younger than its item's cooling waits.
releases_json "$((NOW - 3600))" "$((NOW - 86400))" > "$WORK/releases.json"; serve https://www.kernel.org/releases.json "$WORK/releases.json"
P="$(plan cooling)"
[ "$(action "$P" kernel)" = wait ] || fail "a one-hour-old kernel release did not wait: $(cat "$P/plan.tsv")"
[ ! -f "$P/kernel.json" ] || fail "a waiting item prepared a commit"
releases_json "$((NOW - 3 * 86400))" "$((NOW - 86400))" > "$WORK/releases.json"; serve https://www.kernel.org/releases.json "$WORK/releases.json"
# A release without a date waits too.
jq '{releases: [.releases[] | select(.version != "7.2.10")]}' "$WORK/releases.json" > "$WORK/nodate.json"; serve https://www.kernel.org/releases.json "$WORK/nodate.json"
P="$(plan nodate)"; [ "$(action "$P" kernel)" = wait ] || fail "an undated release did not wait"
serve https://www.kernel.org/releases.json "$WORK/releases.json"
echo "plan cooling: ok"

# Release tags in their line's order (#1994): sort -V ranked fedora-45-v1 above fedora-45-20261111,
# so a built-in on v1 never moved; a channel's release is not listed at all.
[ "$("$E" releases base-image | tr '\n' ' ')" = "fedora-45-v1 fedora-45-20261111 fedora-46-20270101 " ] \
    || fail "release tags out of order: $("$E" releases base-image | tr '\n' ' ')"
ls "$P/issues/" | grep -q 'next' && fail "a channel's release opened an issue: $(ls "$P/issues")"
# A second release that day is newer than the first, by number.
mk_release fedora-45-20261111-2; cp "$FIX/gh-releases.json" "$WORK/gh-releases.saved"
jq --arg now "$(date -u -d "@$NOW" +%FT%TZ)" '[{tag_name: "fedora-45-20261111-2", draft: false, prerelease: false, published_at: $now}] + .' \
    "$WORK/gh-releases.saved" > "$FIX/gh-releases.json"
P="$(plan sameday)"
[ "$(action "$P" base-image)" = pr ] && [ "$(version "$P" base-image)" = fedora-45-20261111-2 ] || fail "base-image: expected a PR for fedora-45-20261111-2: $(cat "$P/plan.tsv")"
cp "$WORK/gh-releases.saved" "$FIX/gh-releases.json"; rm -rf "$FIX/releases/fedora-45-20261111-2"
echo "plan base-image lines: ok"

# The next major's signed file, once published, is read too: a new series issue, no PR.
sign_sums signer 8.0 > "$WORK/sums8.asc"; serve https://cdn.kernel.org/pub/linux/kernel/v8.x/sha256sums.asc "$WORK/sums8.asc"
P="$(plan major)"
[ -f "$P/issues/kernel-8.0.json" ] || fail "a signed v8 sums file did not open a series issue: $(ls "$P/issues")"
[ "$(action "$P" kernel)" = pr ] && [ "$(version "$P" kernel)" = 7.2.10 ] || fail "the 7.2 bump was lost to the new major"
sign_sums other 8.0 > "$WORK/sums8-bad.asc"; serve https://cdn.kernel.org/pub/linux/kernel/v8.x/sha256sums.asc "$WORK/sums8-bad.asc"
"$E" plan "$WORK/plan-major-bad" > "$WORK/major-bad.log" 2>&1 && fail "a v8 sums file signed by another key was ignored rather than failed"
rm -f "$FIX/http/$(http https://cdn.kernel.org/pub/linux/kernel/v8.x/sha256sums.asc)"
# Nothing newer in the series: none. The signed file that lost the newest entry never moves it back.
sign_sums signer 7.2.8 7.2.9 > "$WORK/sums-old.asc"; serve https://cdn.kernel.org/pub/linux/kernel/v7.x/sha256sums.asc "$WORK/sums-old.asc"
P="$(plan none)"; [ "$(action "$P" kernel)" = none ] || fail "an older-only sums file proposed something: $(cat "$P/plan.tsv")"
[ ! -f "$P/issues/kernel-7.3.json" ] || fail "no 7.3 in the file, yet a series issue"
serve https://cdn.kernel.org/pub/linux/kernel/v7.x/sha256sums.asc "$WORK/sums.asc"
# A signature by another key fails the whole run: nothing is proposed.
sign_sums other 7.2.8 7.2.9 7.2.10 > "$WORK/sums-bad.asc"; serve https://cdn.kernel.org/pub/linux/kernel/v7.x/sha256sums.asc "$WORK/sums-bad.asc"
"$E" plan "$WORK/plan-badsig" > "$WORK/badsig.log" 2>&1 && fail "a sums file signed by another key was accepted"
grep -q "signature" "$WORK/badsig.log" || fail "the bad signature was not what failed: $(tail -3 "$WORK/badsig.log")"
serve https://cdn.kernel.org/pub/linux/kernel/v7.x/sha256sums.asc "$WORK/sums.asc"
# The pinned version's sum changing at the publisher is an issue, not a re-pin.
sign_sums signer 7.2.8 7.2.10 > "$WORK/sums-nopin.asc"
{ printf '%s  linux-7.2.9.tar.xz\n' "$(sumof other-7.2.9)"; printf '%s  linux-7.2.10.tar.xz\n' "$(sumof 7.2.10)"; } | "${GPG[@]}" --local-user signer@test.invalid --clearsign > "$WORK/sums-nopin.asc"
serve https://cdn.kernel.org/pub/linux/kernel/v7.x/sha256sums.asc "$WORK/sums-nopin.asc"
P="$(plan mismatch)"
[ "$(action "$P" kernel)" = issue ] && [ -f "$P/issues/kernel-pin-mismatch.json" ] || fail "a changed sum for the pinned version did not become an issue: $(cat "$P/plan.tsv")"
[ ! -f "$P/kernel.json" ] || fail "a mismatched pin still got a bump"
serve https://cdn.kernel.org/pub/linux/kernel/v7.x/sha256sums.asc "$WORK/sums.asc"
# A JetBrains .sha256 that disagrees with the tarball, or a link off the allowlist, fails closed.
serve_text https://download.jetbrains.com/idea/idea-2026.3.tar.gz.sha256 "$(sumof wrong) *idea-2026.3.tar.gz"
"$E" plan "$WORK/plan-jbsum" > "$WORK/jbsum.log" 2>&1 && fail "a tarball that does not hash to its .sha256 was accepted"
serve_text https://download.jetbrains.com/idea/idea-2026.3.tar.gz.sha256 "$S3X *idea-2026.3.tar.gz"
jq '.IIU[0].downloads.linux.link = "https://evil.example/idea-2026.3.tar.gz" | .IIU[0].downloads.linux.checksumLink = "https://evil.example/idea-2026.3.tar.gz.sha256"' "$WORK/jb.json" > "$WORK/jb-evil.json"
serve "$JB_URL" "$WORK/jb-evil.json"
"$E" plan "$WORK/plan-jblink" > "$WORK/jblink.log" 2>&1 && fail "a link off download.jetbrains.com was accepted"
grep -q "evil.example" "$WORK/jblink.log" || fail "the link was not what failed: $(tail -3 "$WORK/jblink.log")"
serve "$JB_URL" "$WORK/jb.json"
# A judgement item gets an issue, never a commit.
yq -i '.items.idea-backend.class = "judgement" | del(.items.idea-backend.verify) | del(.items.idea-backend.within)' "$REPO/upstream/manifest.yaml"
P="$(plan judgement)"
[ "$(action "$P" idea-backend)" = issue ] && [ -f "$P/issues/idea-backend-2026.3.json" ] || fail "a judgement item did not become an issue: $(cat "$P/plan.tsv")"
[ ! -f "$P/idea-backend.json" ] || fail "a judgement item got a commit"
git -C "$REPO" checkout -q -- upstream/manifest.yaml
# A review item (#2210) gets the same commit as a mechanical one, never an issue, and its PR says
# who merges it.
yq -i '.items.idea-backend.class = "review"' "$REPO/upstream/manifest.yaml"
P="$(plan review)"
[ "$(action "$P" idea-backend)" = pr ] && [ "$(version "$P" idea-backend)" = 2026.3 ] || fail "a review item did not become a PR: $(cat "$P/plan.tsv")"
[ ! -f "$P/issues/idea-backend-2026.3.json" ] || fail "a review item got an issue"
jq -r .body "$P/idea-backend.json" | grep -q 'the bot never approves or merges it' || fail "a review item's PR does not say the bot leaves it: $(jq -r .body "$P/idea-backend.json")"
jq -r .body "$P/kernel.json" | grep -q 'only then may the bot approve and merge it' || fail "a mechanical item's PR lost its merge sentence"
R3="$(jq -r .commit "$P/idea-backend.json")"
"$E" verify-trees "$BASE" "$R3" "$APP_LOGIN" yes > "$WORK/verify.log" 2>&1 || { cat "$WORK/verify.log" >&2; fail "the engine's own bump of a review item failed verification"; }
r3bad="$(commit_from "$R3" bad sed -i "s/^    sha256: $S3X\$/    sha256: $(sumof wrong)/" common/src/main/resources/tools/idea-backend.yaml)"
"$E" verify-trees "$BASE" "$r3bad" "$APP_LOGIN" yes > "$WORK/verify.log" 2>&1 && fail "an app PR for a review item with a tampered sum passed"
grep -q "is not main's engine's regeneration" "$WORK/verify.log" || fail "the tampered sum was not what failed: $(cat "$WORK/verify.log")"
git -C "$REPO" checkout -q -- upstream/manifest.yaml
echo "plan fail-closed: ok"

# ---- verify-trees ------------------------------------------------------------------------------------
P="$(plan verify)"
verify() { "$E" verify-trees "$BASE" "$1" "$2" "$3" > "$WORK/verify.log" 2>&1; }
# The app's own regeneration passes; anything beside it fails.
for i in kernel base-image idea-backend jasolo; do
    verify "$(jq -r .commit "$P/$i.json")" "$APP_LOGIN" yes || { cat "$WORK/verify.log" >&2; fail "the engine's own $i bump failed verification"; }
done
J="$(jq -r .commit "$P/jasolo.json")"
jbad="$(commit_from "$J" jbad sed -i "s/$(jar_sum 0.2.1)/$(jar_sum 0.3.0)/" "$JASOLO_YAML")"
verify "$jbad" "$APP_LOGIN" yes && fail "an app PR for jasolo with a sum other than GitHub's digest passed"
verify "$jbad" "someone" yes && fail "a person's jasolo bump with a sum other than GitHub's digest passed"
verify "$J" "someone" yes || { cat "$WORK/verify.log" >&2; fail "a person's jasolo bump with GitHub's digest failed"; }
K="$(jq -r .commit "$P/kernel.json")"
extra="$(commit_from "$K" extra bash -c 'echo x > appliance/kernel/isx.config')"
verify "$extra" "$APP_LOGIN" yes && fail "an app PR with a file beside the bump passed"
tampered="$(commit_from "$K" tampered sed -i 's/^KERNEL_SHA256="./KERNEL_SHA256="f/' appliance/kernel/build-kernel.sh)"
verify "$tampered" "$APP_LOGIN" yes && fail "an app PR with a tampered sum passed"
down="$(commit_from "$BASE" down bash -c "sed -i 's/7.2.9/7.2.8/; s/$(sumof 7.2.9)/$(sumof 7.2.8)/' appliance/kernel/build-kernel.sh")"
verify "$down" "$APP_LOGIN" yes && fail "an app PR that downgrades passed"
series="$(commit_from "$BASE" series bash -c "sed -i 's/7.2.9/7.3.1/; s/$(sumof 7.2.9)/$(sumof 7.3.1)/' appliance/kernel/build-kernel.sh")"
verify "$series" "$APP_LOGIN" yes && fail "an app PR to another series passed"
grep -q "not of series 7.2" "$WORK/verify.log" || fail "the series was not what failed: $(cat "$WORK/verify.log")"
verify "$K" "someone" yes || fail "a person's bump with the publisher's values failed"
verify "$tampered" "someone" yes && fail "a person's bump with a sum the publisher does not give passed"
verify "$K" "$APP_LOGIN" no || fail "a fork's PR by the app's name was not checked as a person's bump"
verify "$series" "someone" yes || fail "a person's move to another series with the publisher's values failed"
noitem="$(commit_from "$BASE" noitem bash -c 'echo y > README.md')"
verify "$noitem" "someone" yes && grep -q "no watched item changed" "$WORK/verify.log" || fail "a PR touching no item did not pass as such"
# The reviewer's case (#2047): a regeneration that fails partway must fail the check, never leave an
# empty sum that passes the diff. The x86 tarball no longer matches its .sha256; the PR pins 2026.3
# with an empty x86 sum.
I3="$(jq -r .commit "$P/idea-backend.json")"
serve_text https://download.jetbrains.com/idea/idea-2026.3.tar.gz.sha256 "$(sumof wrong) *idea-2026.3.tar.gz"
poc="$(commit_from "$I3" poc bash -c "sed -i 's/^    sha256: $S3X\$/    sha256: /' common/src/main/resources/tools/idea-backend.yaml")"
verify "$poc" "$APP_LOGIN" yes && fail "an app PR with an empty sum passed while the re-hash failed"
grep -q 'hashes to' "$WORK/verify.log" || fail "the failed re-hash was not what failed: $(cat "$WORK/verify.log")"
verify "$I3" "someone" yes && fail "a person's bump passed while the publisher's tarball does not match its .sha256"
serve_text https://download.jetbrains.com/idea/idea-2026.3.tar.gz.sha256 "$S3X *idea-2026.3.tar.gz"
# What plan would not propose, verify does not pass: a version inside its cooling period or without
# a date, and one Sanne declined.
jq '.IIU[0].date = (now | strftime("%Y-%m-%d"))' "$WORK/jb.json" > "$WORK/jb-fresh.json"; serve "$JB_URL" "$WORK/jb-fresh.json"
verify "$I3" "$APP_LOGIN" yes && fail "an app PR for a release inside its cooling period passed"
grep -q 'cooling' "$WORK/verify.log" || fail "cooling was not what failed: $(cat "$WORK/verify.log")"
jq 'del(.IIU[0].date)' "$WORK/jb.json" > "$WORK/jb-undated.json"; serve "$JB_URL" "$WORK/jb-undated.json"
verify "$I3" "$APP_LOGIN" yes && fail "an app PR for an undated release passed"
serve "$JB_URL" "$WORK/jb.json"
jq -n '[{body: "x\n\n<!-- upstream-watch: kernel 7.2.10 -->", mergedAt: null, headRepositoryOwner: {login: "Sanne"}, isCrossRepository: false}]' > "$FIX/prs-closed.json"
verify "$K" "$APP_LOGIN" yes && fail "an app PR for a declined version passed"
grep -q 'declined' "$WORK/verify.log" || fail "the declined marker was not what failed: $(cat "$WORK/verify.log")"
P2="$(plan declined)"; [ "$(action "$P2" kernel)" = none ] || fail "plan proposed a declined version: $(cat "$P2/plan.tsv")"
# The none row names the version main pins, which publish hands to close_stale, not the declined one.
[ "$(version "$P2" kernel)" = 7.2.9 ] || fail "a declined row carries the declined version, not main's pin: $(cat "$P2/plan.tsv")"
jq '.[0].mergedAt = "2026-01-01T00:00:00Z"' "$FIX/prs-closed.json" > "$FIX/tmp.json" && mv "$FIX/tmp.json" "$FIX/prs-closed.json"
verify "$K" "$APP_LOGIN" yes || fail "a merged PR of the same version counted as declined"
jq -n '[{body: "x\n\n<!-- upstream-watch: kernel 7.2.10 -->", mergedAt: null, headRepositoryOwner: {login: "someone"}, isCrossRepository: true}]' > "$FIX/prs-closed.json"
verify "$K" "$APP_LOGIN" yes || fail "a fork's closed PR counted as declined"
rm -f "$FIX/prs-closed.json"
# An app PR for an item the manifest calls judgement fails whatever it carries.
yq -i '.items.kernel.class = "judgement" | del(.items.kernel.verify) | del(.items.kernel.within)' "$REPO/upstream/manifest.yaml"
verify "$K" "$APP_LOGIN" yes && fail "an app PR for a judgement item passed"
grep -q "is class judgement" "$WORK/verify.log" || fail "the class was not what failed: $(cat "$WORK/verify.log")"
git -C "$REPO" checkout -q -- upstream/manifest.yaml
# verify-pr: the same through the PR's refs and gh.
git -C "$REPO" push -q origin "$K:refs/pull/5/head"
jq -n --arg sha "$K" --arg login "$APP_LOGIN" '{head: {sha: $sha, repo: {full_name: "Sanne/incus-spawn"}}, base: {ref: "main", repo: {full_name: "Sanne/incus-spawn"}}, user: {login: $login}}' > "$FIX/pr-5.json"
"$E" verify-pr 5 > "$WORK/vpr.log" 2>&1 || { cat "$WORK/vpr.log" >&2; fail "verify-pr 5 failed"; }
grep -q "exactly the engine's regeneration" "$WORK/vpr.log" || fail "verify-pr did not take the app path: $(cat "$WORK/vpr.log")"
echo "verify: ok"

# ---- issues and track --------------------------------------------------------------------------------
: > "$CALLS"
"$E" issues "$P" > /dev/null || fail "issues failed"
[ "$(grep -c '^gh issue create' "$CALLS")" = 3 ] || fail "expected three issues created: $(grep 'issue create' "$CALLS" | wc -l)"
: > "$CALLS"; "$E" issues "$P" > /dev/null
[ "$(grep -c '^gh issue create' "$CALLS")" = 0 ] || fail "an open issue was created again"
# An EOL issue of another series, old title or new, is closed; nothing else is.
echo '[{"number": 41, "title": "[kernel] 7.1 is EOL"}, {"number": 42, "title": "[upstream] kernel: series 7.0 is EOL"}, {"number": 43, "title": "[upstream] kernel: 7.3 available"}]' > "$FIX/issues.json"
: > "$CALLS"; "$E" issues "$P" > /dev/null
[ "$(grep -c '^gh issue close 41 ' "$CALLS")" = 1 ] && [ "$(grep -c '^gh issue close 42 ' "$CALLS")" = 1 ] && [ "$(grep -c '^gh issue close 43 ' "$CALLS")" = 0 ] \
    || fail "stale EOL issues were not closed, or another was: $(grep 'issue close' "$CALLS")"
: > "$CALLS"; echo '[]' > "$FIX/issues.json"
"$E" track failure https://run/1 > /dev/null; "$E" track failure https://run/2 > /dev/null; "$E" track success https://run/3 > /dev/null
[ "$(grep -c '^gh issue create' "$CALLS")" = 1 ] && [ "$(grep -c '^gh issue comment' "$CALLS")" = 1 ] && [ "$(grep -c '^gh issue close' "$CALLS")" = 1 ] \
    || fail "track: expected one create, one comment, one close: $(grep '^gh issue' "$CALLS")"
echo "issues: ok"
echo "test-engine: ok"
