#!/bin/bash
# Runs update-base-image.yml's three steps, as the workflow_dispatch backstop runs them, against a
# fake images release that carries -vm-direct and SHA256SUMS.v2 (#1255), with gh, curl and the
# remote faked. Checks the switch to -vm-direct, with both arches' checksums, from a stock
# built-in; after a stock-image bump of the same release was merged; and while that bump's
# branch is still open; a routine bump of a built-in already on -vm-direct; the refusal to move
# such a built-in back to the stock image when a release lacks -vm-direct; and that the PR names
# a switch of VM image and a release published again under its tag. The built-in it starts from is
# written here, held to minimal.yaml's keys. Needs bash, git and mikefarah yq.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TAG=fedora-44-20991231
OLD_TAG=fedora-44-20000101
H() { printf '%064x' "$1"; }

WORKFLOW="$REPO/.github/workflows/update-base-image.yml"
for pair in "resolve:Resolve release + checksums" "update:Update minimal.yaml" "pr:Open pull request"; do
    STEP="${pair#*:}" yq -r '.jobs.bump.steps[] | select(.name == strenv(STEP)) | .run' "$WORKFLOW" > "$WORK/${pair%%:*}.sh"
    [ -s "$WORK/${pair%%:*}.sh" ] || { echo "FAIL: no step named '${pair#*:}' in $WORKFLOW" >&2; exit 1; }
done

mkdir -p "$WORK/bin" "$WORK/release"
{ echo "$(H 11)  fedora-44-aarch64-vm.tar.xz"; echo "$(H 12)  fedora-44-aarch64.tar.xz"
  echo "$(H 13)  fedora-44-x86_64-vm.tar.xz"; echo "$(H 14)  fedora-44-x86_64.tar.xz"; } > "$WORK/release/SHA256SUMS"
{ cat "$WORK/release/SHA256SUMS"
  echo "$(H 15)  fedora-44-x86_64-vm-direct.tar.xz"; echo "$(H 16)  fedora-44-aarch64-vm-direct.tar.xz"; } > "$WORK/release/SHA256SUMS.v2"
cat > "$WORK/bin/gh" <<EOF
#!/bin/bash
case "\$*" in
  *"/releases --jq"*) echo $TAG ;;
  *"releases/tags/$TAG"*"SHA256SUMS.v2"*) [ -n "\${NO_V2:-}" ] || echo https://example.invalid/SHA256SUMS.v2 ;;
  *"releases/tags/$TAG"*'SHA256SUMS")'*) echo https://example.invalid/SHA256SUMS ;;
  "pr create"*) printf '%s\n' "\$@" >> "$WORK/prs" ;;
  *) echo "unexpected: gh \$*" >&2; exit 2 ;;
esac
EOF
cat > "$WORK/bin/curl" <<EOF
#!/bin/bash
url=""; out=""
while [ \$# -gt 0 ]; do case "\$1" in -o) out=\$2; shift ;; https://*) url=\$1 ;; esac; shift; done
cp "$WORK/release/\${url##*/}" "\$out"
EOF
chmod +x "$WORK/bin/"*

fail() { echo "FAIL: $*" >&2; exit 1; }

# run <minimal.yaml> [branch already on the remote]: prints the resulting minimal.yaml
run() {
    local dir="$WORK/run-$RANDOM"
    mkdir -p "$dir/common/src/main/resources/images"
    cp "$1" "$dir/common/src/main/resources/images/minimal.yaml"
    (
        cd "$dir"
        git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm init
        git init -q --bare remote.git && git remote add origin remote.git && git push -q origin main
        [ -n "${2:-}" ] && git push -q origin "main:refs/heads/$2"
        export PATH="$WORK/bin:$PATH" GITHUB_OUTPUT="$dir/out" GH_TOKEN=x
        rm -f "$WORK/prs"
        EVENT_NAME=workflow_dispatch PAYLOAD=null bash -eo pipefail "$WORK/resolve.sh" >/dev/null
        set -a; . "$dir/out"; set +a
        [ "$changed" = true ] || { echo "UNCHANGED"; exit 0; }
        TAG=$tag C_X86=$c_x86 C_ARM=$c_arm V_X86=$v_x86 V_ARM=$v_arm VM_KIND=$vm_kind \
            bash -eo pipefail "$WORK/update.sh" >/dev/null
        TAG=$tag CURRENT_TAG=$current VM_KIND=$vm_kind REPUBLISHED=${republished:-} VM_SWITCH=${vm_switch:-} \
            C_X86=$c_x86 C_ARM=$c_arm V_X86=$v_x86 V_ARM=$v_arm bash -eo pipefail "$WORK/pr.sh" >/dev/null 2>&1
        [ -f "$WORK/prs" ] || { echo "NO-PR"; exit 0; }
        cat common/src/main/resources/images/minimal.yaml
        sed -n '/^--title$/{n;s/^/title: /;p}' "$WORK/prs"
        echo "branch: $(git branch --show-current)"
    )
}

expect_direct() {
    local out what="$2"
    out="$(grep -v '^title: \|^branch: ' <<<"$1")"
    grep -q 'vm_image_url: .*/{tag}/fedora-44-{arch}-vm-direct.tar.xz$' <<<"$out" || fail "$what: vm_image_url not -vm-direct: $out"
    grep -q "image_tag: $TAG" <<<"$out" || fail "$what: tag not $TAG"
    [ "$(yq -r '.vm_image_sha256.x86_64' <<<"$out")" = "$(H 15)" ] || fail "$what: x86_64 VM checksum is not -vm-direct's"
    [ "$(yq -r '.vm_image_sha256.aarch64' <<<"$out")" = "$(H 16)" ] || fail "$what: aarch64 VM checksum is not -vm-direct's"
    [ "$(yq -r '.image_sha256.x86_64' <<<"$out")" = "$(H 14)" ] || fail "$what: x86_64 container checksum"
    [ "$(yq -r '.image_sha256.aarch64' <<<"$out")" = "$(H 12)" ] || fail "$what: aarch64 container checksum"
    echo "ok: $what"
}

# A stock built-in of an older release, written here rather than taken from the repository's
# minimal.yaml: the bump this checks moves that file to -vm-direct, and a fixture derived from
# it would stop being the state the checks start from.
BUILTIN="$WORK/stock-builtin.yaml"
cat > "$BUILTIN" <<EOF
name: tpl-minimal
description: Base OS only
image: fedora-44-base
image_url: https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-44-{arch}.tar.xz
vm_image_url: https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-44-{arch}-vm.tar.xz
image_tag: $OLD_TAG
image_sha256:
  x86_64: $(H 1)
  aarch64: $(H 2)
vm_image_sha256:
  x86_64: $(H 3)
  aarch64: $(H 4)
EOF
# Written by hand, so it is held to the real file's shape, nested keys included: a key renamed
# there must be renamed here.
shape() { yq -o=json -I=0 '[.. | path | join(".")] | sort' "$1"; }
WANT_SHAPE="$(shape "$REPO/common/src/main/resources/images/minimal.yaml")"
[ "$(shape "$BUILTIN")" = "$WANT_SHAPE" ] \
    || fail "the fixture's keys differ from minimal.yaml's: fixture $(shape "$BUILTIN"), minimal.yaml $WANT_SHAPE"
out="$(run "$BUILTIN")"
expect_direct "$out" "stock built-in, older tag"
grep -qx "title: Update base image to $TAG, VM image built from scratch" <<<"$out" || fail "the switch is not in the title: $(grep '^title:' <<<"$out")"

yq ".image_tag = \"$TAG\" | .image_sha256.x86_64 = \"$(H 14)\" | .image_sha256.aarch64 = \"$(H 12)\"
    | .vm_image_sha256.x86_64 = \"$(H 13)\" | .vm_image_sha256.aarch64 = \"$(H 11)\"" "$BUILTIN" > "$WORK/stock-bump.yaml"
out="$(run "$WORK/stock-bump.yaml")"
expect_direct "$out" "stock-image bump of the same release already merged"
grep -qx "title: Update base image to $TAG, VM image built from scratch" <<<"$out" \
    || fail "a switch after a stock-image bump of the same release is not a re-publish: $(grep '^title:' <<<"$out")"
expect_direct "$(run "$BUILTIN" "bot/update-base-image-$TAG")" "stock-image bump of the same release still open"

yq ".vm_image_url |= sub(\"[^/]+\$\"; \"fedora-44-{arch}-vm-direct.tar.xz\")
    | .vm_image_sha256.x86_64 = \"$(H 15)\" | .vm_image_sha256.aarch64 = \"$(H 16)\"" \
    "$WORK/stock-bump.yaml" > "$WORK/direct.yaml"
[ "$(run "$WORK/direct.yaml")" = UNCHANGED ] || fail "a built-in already on this release's -vm-direct is bumped again"
echo "ok: already current"

# main once the first -vm-direct bump has merged: a built-in already on -vm-direct, of an older
# release. A routine bump must not be called a switch of image, nor a re-publish.
yq ".image_tag = \"$OLD_TAG\" | .image_sha256.x86_64 = \"$(H 5)\" | .image_sha256.aarch64 = \"$(H 6)\"
    | .vm_image_sha256.x86_64 = \"$(H 7)\" | .vm_image_sha256.aarch64 = \"$(H 8)\"" "$WORK/direct.yaml" > "$WORK/direct-older.yaml"
out="$(run "$WORK/direct-older.yaml")"
expect_direct "$out" "built-in already on -vm-direct, older tag"
grep -qx "title: Update base image to $TAG" <<<"$out" \
    || fail "a routine bump is titled as more than one: $(grep '^title:' <<<"$out")"
grep -qx "branch: bot/update-base-image-$TAG-vm-direct" <<<"$out" \
    || fail "a routine -vm-direct bump's branch: $(grep '^branch:' <<<"$out")"
echo "ok: routine bump of a built-in already on -vm-direct"

# The same built-in, and a release without -vm-direct: the bump stops rather than move every
# user's VMs back to the stock image.
NO_V2=1 run "$WORK/direct-older.yaml" >/dev/null 2>&1 && fail "a release without -vm-direct moved a -vm-direct built-in back to stock"
echo "ok: no move back to the stock image"

# The release published again under its tag: a file the built-in already names has another checksum.
yq ".image_sha256.aarch64 = \"$(H 99)\"" "$WORK/direct.yaml" > "$WORK/republished.yaml"
out="$(run "$WORK/republished.yaml")"
expect_direct "$out" "release published again under its tag"
grep -qx "title: Base image $TAG was published again: new checksums" <<<"$out" \
    || fail "a re-publish is not named in the title: $(grep '^title:' <<<"$out")"
NAMED="$(printf '%s %s %s %s' "$(H 14)" "$(H 12)" "$(H 15)" "$(H 16)" | sha256sum | cut -c1-12)"
grep -qx "branch: bot/update-base-image-$TAG-vm-direct-$NAMED" <<<"$out" \
    || fail "a re-publish's branch is not named by all four checksums: $(grep '^branch:' <<<"$out")"
echo "ok: re-publish named, branch by all four checksums"
