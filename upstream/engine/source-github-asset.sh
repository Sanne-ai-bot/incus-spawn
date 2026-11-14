# Source type github-asset (#2206): one release asset of a third-party GitHub repository, pinned as
# the one download of a tool YAML whose URL is under https://github.com/<source.repo>/releases/download/,
# jasolo today. The publisher writes no sums file and signs nothing, so the sum is the asset's
# `digest` in GitHub's releases API: GitHub computes it at upload, so it proves the bytes are what the
# release holds now, not who built them (design/upstream.md "The github-asset source: GitHub's digest"). Unless
# UPSTREAM_REHASH=0 the download is hashed again and must agree. The tag is the version, plain
# semver; a download URL that is not exactly the release's asset in source.repo is refused, whatever
# the API says. A series is 0.<minor> below 1.0 and <major> from 1.0, so a breaking release is an issue.

TOOL_FILE="$(item_list "$ITEM" files | head -1)"
SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+$'
_repo() { item "$ITEM" source.repo; }
_asset() { local a; a="$(item "$ITEM" source.asset)"; printf '%s' "${a//\{version\}/$1}"; }
_prefix() { printf 'https://github.com/%s/releases/download/' "$(_repo)"; }
_expected_url() { printf '%s%s/%s' "$(_prefix)" "$1" "$(_asset "$1")"; }

# The index of the one download under the repository's release URLs; none or several fail.
_index() {
    local urls n=0 i=0 found="" u
    urls="$(yq -r '.downloads[].url' "$TREE/$TOOL_FILE")"
    while IFS= read -r u; do
        [[ "$u" == "$(_prefix)"* ]] && { found="$i"; n=$((n + 1)); }
        i=$((i + 1))
    done <<<"$urls"
    [[ "$n" == 1 ]] || die "$TOOL_FILE has $n downloads under $(_prefix), not one"
    printf '%s' "$found"
}
_url() { yq -r ".downloads[$(_index)].url" "$TREE/$TOOL_FILE"; }
_sum() { yq -r ".downloads[$(_index)].sha256" "$TREE/$TOOL_FILE"; }
_desc() { yq -r '.description' "$TREE/$TOOL_FILE"; }

src_validate() {
    [[ "$(_repo)" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$ ]] || die "manifest: item $ITEM: source.repo must be owner/name"
    [[ "$(item "$ITEM" source.asset)" =~ ^[A-Za-z0-9._-]*\{version\}[A-Za-z0-9._-]*$ ]] \
        || die "manifest: item $ITEM: source.asset must be a file name holding {version} once"
}

src_current() {
    local url v
    url="$(_url)"
    v="${url#"$(_prefix)"}"; v="${v%%/*}"
    [[ "$v" =~ $SEMVER_RE && "$url" == "$(_expected_url "$v")" ]] \
        || die "$TOOL_FILE's URL is not a $(_repo) release's $(item "$ITEM" source.asset): $url"
    printf '%s' "$v"
}
src_series() {
    [[ "$1" =~ $SEMVER_RE ]] || return 1
    if [[ "${1%%.*}" == 0 ]]; then printf '%s' "$1" | cut -d. -f1-2; else printf '%s' "${1%%.*}"; fi
}
src_verifier() { echo asset-digest; }
src_first_party() { return 1; }

# Every published, non-draft, non-prerelease release with a semver tag and the asset, oldest first.
src_releases() {
    gh api --paginate "repos/$(_repo)/releases" \
        --jq '.[] | select(.draft == false and .prerelease == false) | [.tag_name, (.assets // [] | map(.name) | join("/"))] | @tsv' \
        | while IFS=$'\t' read -r tag names; do
            [[ "$tag" =~ $SEMVER_RE ]] || continue
            if [[ "/$names/" == *"/$(_asset "$tag")/"* ]]; then printf '%s\n' "$tag"; fi
        done | sort -V
}

# _release <tag>: the release's JSON, once per run.
_release() {
    local f="$UPSTREAM_CACHE/github-asset-$ITEM-$1.json"
    [[ "$1" =~ $SEMVER_RE ]] || die "not a semver tag: '$1'"
    [[ -s "$f" ]] || gh api "repos/$(_repo)/releases/tags/$1" --jq . > "$f" || die "$(_repo) has no release $1"
    printf '%s' "$f"
}

src_released_at() {
    local at
    at="$(jq -r 'select(.draft == false and .prerelease == false) | .published_at // empty' "$(_release "$1")")"
    [[ -n "$at" ]] || die "release $1 has no published_at"
    date -u -d "$at" +%s
}

# _asset_sum <tag>: the sum GitHub's digest gives the asset and, unless UPSTREAM_REHASH=0, the
# download's own hash, which must agree.
_asset_sum() {
    local tag="$1" rel name url want asset digest sum f have
    rel="$(_release "$tag")"
    name="$(_asset "$tag")"; want="$(_expected_url "$tag")"
    jq -e '.draft == false and .prerelease == false' "$rel" > /dev/null || die "release $tag is a draft or a prerelease"
    [[ "$(jq -r '.tag_name' "$rel")" == "$tag" ]] || die "the release fetched for $tag names another tag"
    asset="$(jq -c --arg n "$name" '[.assets[]? | select(.name == $n)] | if length == 1 then .[0] else empty end' "$rel")"
    [[ -n "$asset" ]] || die "release $tag has no single asset $name"
    [[ "$(jq -r '.state // empty' <<<"$asset")" == uploaded ]] || die "release $tag: $name is not uploaded"
    url="$(jq -r '.browser_download_url // empty' <<<"$asset")"
    [[ "$url" == "$want" ]] || die "release $tag: $name downloads from $url, not $want"
    digest="$(jq -r '.digest // empty' <<<"$asset")"
    [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || die "release $tag: $name has no sha256 digest ('$digest')"
    sum="${digest#sha256:}"
    if [[ "${UPSTREAM_REHASH:-1}" != 0 ]]; then
        f="$(mktemp)"
        fetch "$url" "$f"
        have="$(sha256sum "$f" | cut -d' ' -f1)"
        rm -f "$f"
        [[ "$have" == "$sum" ]] || die "$url hashes to $have, but GitHub's digest says $sum"
    fi
    printf '%s' "$sum"
}

src_apply() {
    local version="$1" f="$TREE/$TOOL_FILE" cur sum desc rest words
    [[ "$version" =~ $SEMVER_RE ]] || die "not a semver version: '$version'"
    cur="$(src_current)"
    sum="$(_asset_sum "$version")"
    is_sha256 "$sum" || die "release $version: no sum for $(_asset "$version")"
    desc="$(_desc)"
    # The description names the version once, as a word (jasolo 0.2.0, an MCP server ...).
    rest="${desc//"$cur"/}"
    words="$( { grep -oE "(^|[^0-9A-Za-z.])${cur//./\\.}([^0-9A-Za-z.]|\$)" <<<"$desc" || true; } | wc -l)"
    [[ $(( ${#desc} - ${#rest} )) == "${#cur}" && "$words" == 1 ]] \
        || die "$TOOL_FILE's description does not name $cur exactly once"
    replace_once "$f" "$(_url)" "$(_expected_url "$version")"
    replace_once "$f" "$(_sum)" "$sum"
    replace_once "$f" "description: $desc" "description: ${desc/"$cur"/"$version"}"
    [[ "$(src_current)" == "$version" && "$(_sum)" == "$sum" ]] || die "failed to rewrite $TOOL_FILE"
}

src_fields() {
    printf 'description=%s\nurl=%s\nsha256=%s\n' "$(_desc)" "$(_url)" "$(_sum)"
}

src_describe() {
    cat <<MD
Bumps \`$ITEM\` to $1, the newest release of \`$(_repo)\` within its series.

The download is the release's asset \`$(_asset "$1")\`, at \`$(_expected_url "$1")\` only; its sum is the \`digest\` GitHub's releases API gives the asset, and the engine re-hashed the download against it. GitHub computes that digest at upload, so it proves the file is what the release holds, not who built it. Release notes: https://github.com/$(_repo)/releases/tag/$1

Users who want another version override the tool with a same-name \`~/.config/incus-spawn/tools/$ITEM.yaml\`.
MD
}
