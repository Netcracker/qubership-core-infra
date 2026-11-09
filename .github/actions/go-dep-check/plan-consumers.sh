#!/usr/bin/env bash
# Plans the reference Go consumers of a pull request.
# A consumer is planned when a module's non-test build list differs from the base and the consumer's
# go.sum on its default branch names that module. A go.sum that cannot be read is planned anyway.
# With no base, the build list is not compared.
#
# The build list is the go and toolchain lines plus module@version (and its replace) of every module
# selected by packages reachable from non-test code. A package imported only by tests, and anything
# reached only from such a package, is outside the list: a consumer does not compile it.
#
# env:  CONFIG    effective gate configuration, JSON
#       MODULES   JSON array of module directories
#       REPO_DIR  repository checkout (default .)
#       BASE_REF  base revision; empty skips the build-list comparison
# stdout: JSON array of {repo, module, tests, lib}
# exit:  0 the array is written; 2 the inputs cannot be read
set -uo pipefail
export LC_ALL=C

here=$(cd "$(dirname "$0")" && pwd)
REPO=${REPO_DIR:-.}
REPO=$(cd "$REPO" && pwd) || exit 2

if ! printf '%s' "${CONFIG:-}" | jq -e . >/dev/null; then
  echo "plan: CONFIG is not JSON" >&2
  exit 2
fi
if printf '%s' "$CONFIG" | jq -e '.consumers == false' >/dev/null; then
  echo '[]'
  exit 0
fi
if ! printf '%s' "${MODULES:-}" | jq -e 'type == "array"' >/dev/null; then
  echo "plan: MODULES is not a JSON array" >&2
  exit 2
fi

work=$(mktemp -d)
base_tree=
cleanup() {
  if [[ -n $base_tree ]]; then git -C "$REPO" worktree remove -f "$base_tree" >/dev/null 2>&1; fi
  rm -rf "$work"
}
trap cleanup EXIT

if [[ -n ${BASE_REF:-} ]]; then
  base_tree=$work/base
  if ! git -C "$REPO" worktree add -q --detach "$base_tree" "$BASE_REF"; then
    echo "::warning::plan: cannot check out $BASE_REF, planning every consumer" >&2
    base_tree=
  fi
fi

# Packages a consumer can compile are those reachable by non-test imports (visible-packages.jq).
list() {
  local dir=$1
  shift
  (cd "$dir" && GOTOOLCHAIN=local GOFLAGS=-mod=readonly go "$@" | tr -d '\r')
}

build_list() {
  local dir=$1 out=$2 mod json err visible=() visible_text
  rm -f "$out.mod"
  mod=$(list "$dir" list -m) || return 1
  printf '%s\n' "$mod" > "$out.mod"
  json=$(list "$dir" list -e -json ./...) || return 1
  err=$(printf '%s' "$json" | jq -s 'any(.[]; .Error != null)' | tr -d '\r') || return 1
  if [[ $err == true ]]; then
    echo "plan: $dir: a package does not list" >&2
    return 1
  fi
  if ! visible_text=$(printf '%s' "$json" | jq -s -r --arg mod "$mod" -f "$here/visible-packages.jq" | tr -d '\r'); then
    return 1
  fi
  if [[ -n $visible_text ]]; then
    mapfile -t visible <<< "$visible_text"
  fi
  if ((${#visible[@]})); then
    if ! list "$dir" list -deps \
      -f '{{if .Module}}{{.Module.Path}} {{.Module.Version}}{{with .Module.Replace}} => {{.Path}}{{if .Version}} {{.Version}}{{end}}{{end}}{{end}}' \
      "${visible[@]}" > "$out.deps"; then
      return 1
    fi
  else
    : > "$out.deps"
  fi
  {
    grep -E '^(go|toolchain)[[:space:]]' "$dir/go.mod" || true
    awk -v own="$mod" 'NF && $1 != own' "$out.deps"
  } | sed 's/[[:space:]]\+/ /g; s/[[:space:]]$//' | sort -u > "$out"
}

list_changed() {
  local lib=$1
  rm -f "$work/head.list.mod"
  if [[ -z ${BASE_REF:-} || -z $base_tree ]]; then
    return 0
  fi
  if [[ ! -f $base_tree/$lib/go.mod ]]; then
    echo "plan: $lib: no module on $BASE_REF" >&2
    return 0
  fi
  if ! build_list "$REPO/$lib" "$work/head.list"; then
    echo "::warning::plan: cannot read the non-test build list of $lib, checking its consumers" >&2
    return 0
  fi
  if ! build_list "$base_tree/$lib" "$work/base.list"; then
    echo "::warning::plan: cannot read the non-test build list of $lib at $BASE_REF, checking its consumers" >&2
    return 0
  fi
  if cmp -s "$work/head.list" "$work/base.list"; then
    echo "plan: $lib: non-test build list unchanged" >&2
    return 1
  fi
  echo "plan: $lib: non-test build list changed" >&2
  diff -u "$work/base.list" "$work/head.list" >&2 || true
  return 0
}

mapfile -t libs < <(printf '%s' "$MODULES" | jq -r '.[]' | tr -d '\r')
list=$(printf '%s' "$CONFIG" | jq -c '(.consumers // {}) | .go // [] | .[]' | tr -d '\r')
consumers=$(for lib in "${libs[@]}"; do
  list_changed "$lib" || continue
  if [[ -f $work/head.list.mod ]]; then
    mod=$(tr -d '\r' < "$work/head.list.mod")
  else
    mod=$(list "$REPO/$lib" list -m) || continue
  fi
  while read -r c; do
    [[ -n $c ]] || continue
    r=$(jq -r .repo <<< "$c") sum=$(jq -r .module <<< "$c")/go.sum
    if ! s=$("${CURL:-curl}" -fsSL --retry 3 "https://raw.githubusercontent.com/$r/HEAD/${sum#./}"); then
      echo "::warning::cannot read go.sum of $r/${sum%/go.sum}, checking it anyway" >&2
    elif ! cut -d' ' -f1 <<< "$s" | grep -qxF -- "$mod"; then
      continue
    fi
    jq -c --arg lib "$lib" '. + {lib: $lib}' <<< "$c" | tr -d '\r'
  done <<< "$list"
done | jq -sc .)
echo "$consumers"
