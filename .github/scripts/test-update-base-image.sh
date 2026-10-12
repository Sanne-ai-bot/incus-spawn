#!/bin/bash
# Runs update-base-image.yml's three steps, as the workflow_dispatch backstop runs them, against a
# fake images release that carries -vm-direct and SHA256SUMS.v2 (#1255), with gh, curl and the
# remote faked. Checks the switch to -vm-direct, with both arches' checksums, from a stock
# built-in; after a stock-image bump of the same release was merged; and while that bump's
# branch is still open. Needs bash, git, python3 with PyYAML, and mikefarah yq.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TAG=fedora-44-20991231
H() { printf '%064x' "$1"; }

python3 - "$REPO/.github/workflows/update-base-image.yml" "$WORK" <<'PY'
import sys, yaml
steps = yaml.safe_load(open(sys.argv[1]))['jobs']['bump']['steps']
for step, name in ((steps[1], 'resolve'), (steps[2], 'update'), (steps[3], 'pr')):
    open(f"{sys.argv[2]}/{name}.sh", 'w').write(step['run'])
PY

mkdir -p "$WORK/bin" "$WORK/release"
{ echo "$(H 11)  fedora-44-aarch64-vm.tar.xz"; echo "$(H 12)  fedora-44-aarch64.tar.xz"
  echo "$(H 13)  fedora-44-x86_64-vm.tar.xz"; echo "$(H 14)  fedora-44-x86_64.tar.xz"; } > "$WORK/release/SHA256SUMS"
{ cat "$WORK/release/SHA256SUMS"
  echo "$(H 15)  fedora-44-x86_64-vm-direct.tar.xz"; echo "$(H 16)  fedora-44-aarch64-vm-direct.tar.xz"; } > "$WORK/release/SHA256SUMS.v2"
cat > "$WORK/bin/gh" <<EOF
#!/bin/bash
case "\$*" in
  *"/releases --jq"*) echo $TAG ;;
  *"releases/tags/$TAG"*"SHA256SUMS.v2"*) echo https://example.invalid/SHA256SUMS.v2 ;;
  *"releases/tags/$TAG"*'SHA256SUMS")'*) echo https://example.invalid/SHA256SUMS ;;
  "pr create"*) echo "PR \$*" >> "$WORK/prs" ;;
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
        TAG=$tag CURRENT_TAG=$current VM_KIND=$vm_kind bash -eo pipefail "$WORK/pr.sh" >/dev/null 2>&1
        [ -f "$WORK/prs" ] || { echo "NO-PR"; exit 0; }
        cat common/src/main/resources/images/minimal.yaml
    )
}

expect_direct() {
    local out="$1" what="$2"
    grep -q 'vm_image_url: .*/{tag}/fedora-44-{arch}-vm-direct.tar.xz$' <<<"$out" || fail "$what: vm_image_url not -vm-direct: $out"
    grep -q "image_tag: $TAG" <<<"$out" || fail "$what: tag not $TAG"
    [ "$(yq -r '.vm_image_sha256.x86_64' <<<"$out")" = "$(H 15)" ] || fail "$what: x86_64 VM checksum is not -vm-direct's"
    [ "$(yq -r '.vm_image_sha256.aarch64' <<<"$out")" = "$(H 16)" ] || fail "$what: aarch64 VM checksum is not -vm-direct's"
    [ "$(yq -r '.image_sha256.x86_64' <<<"$out")" = "$(H 14)" ] || fail "$what: x86_64 container checksum"
    [ "$(yq -r '.image_sha256.aarch64' <<<"$out")" = "$(H 12)" ] || fail "$what: aarch64 container checksum"
    echo "ok: $what"
}

BUILTIN="$REPO/common/src/main/resources/images/minimal.yaml"
expect_direct "$(run "$BUILTIN")" "stock built-in, older tag"

yq ".image_tag = \"$TAG\" | .image_sha256.x86_64 = \"$(H 14)\" | .image_sha256.aarch64 = \"$(H 12)\"
    | .vm_image_sha256.x86_64 = \"$(H 13)\" | .vm_image_sha256.aarch64 = \"$(H 11)\"" "$BUILTIN" > "$WORK/stock-bump.yaml"
expect_direct "$(run "$WORK/stock-bump.yaml")" "stock-image bump of the same release already merged"
expect_direct "$(run "$BUILTIN" "bot/update-base-image-$TAG")" "stock-image bump of the same release still open"

VM_URL="$(yq -r '.vm_image_url' "$BUILTIN" | sed -E 's#[^/]+$#fedora-44-{arch}-vm-direct.tar.xz#')"
yq ".vm_image_url = \"$VM_URL\" | .vm_image_sha256.x86_64 = \"$(H 15)\" | .vm_image_sha256.aarch64 = \"$(H 16)\"" \
    "$WORK/stock-bump.yaml" > "$WORK/direct.yaml"
[ "$(run "$WORK/direct.yaml")" = UNCHANGED ] || fail "a built-in already on this release's -vm-direct is bumped again"
echo "ok: already current"
