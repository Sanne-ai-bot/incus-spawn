#!/bin/bash
# Checks the four base-image checksums of minimal.yaml (or the file given) against the sums files of
# the release image_tag names (#1678): each of image_sha256 and vm_image_sha256, per arch, must be the
# release's sum for the asset image_url or vm_image_url downloads. So the pairing is checked as well
# as the digits: -vm is the VM disk, the bare name the container rootfs, each in two arches. Run by
# verify-base-image-checksums.yml on a pull request that changes the file, and by update-base-image.yml
# on the file it bumped, whose PR shows this output: a Markdown table of each field, its asset and its
# sum, then the command that repeats the check. Needs gh and mikefarah yq.
set -euo pipefail
. "$(dirname "$0")/base-image-sums.sh"
YAML="${1:-$(cd "$(dirname "$0")/../.." && pwd)/common/src/main/resources/images/minimal.yaml}"

TAG="$(yq -r '.image_tag' "$YAML")"
IMAGE_URL="$(yq -r '.image_url' "$YAML")"
VM_URL="$(yq -r '.vm_image_url' "$YAML")"
REPO="$(release_repo "$IMAGE_URL" "$VM_URL")"
SUMS="$(mktemp -d)"
trap 'rm -rf "$SUMS"' EXIT
fetch_release_sums "$REPO" "$TAG" "$SUMS"

failed=0
echo "| Field | Asset | SHA-256 |"
echo "|---|---|---|"
for field in image_sha256 vm_image_sha256; do
    url="$IMAGE_URL"
    [[ "$field" == vm_image_sha256 ]] && url="$VM_URL"
    for arch in x86_64 aarch64; do
        asset="$(asset_name "$url" "$TAG" "$arch")"
        have="$(yq -r ".$field.$arch" "$YAML")"
        if ! want="$(release_sum "$SUMS" "$asset")"; then
            echo "FAIL: $field.$arch: no usable sum for $asset in release $TAG" >&2
            failed=1
        elif [[ "$have" != "$want" ]]; then
            echo "FAIL: $field.$arch is $have, but release $TAG lists $want for $asset" >&2
            failed=1
        else
            echo "| \`$field.$arch\` | \`$asset\` | \`$want\` |"
        fi
    done
done

echo
echo "To repeat the check: \`$(repeat_command "$REPO" "$TAG" "$SUMS")\`"
exit "$failed"
