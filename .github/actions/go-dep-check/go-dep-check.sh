#!/usr/bin/env bash
# go-library adapter of the dependency gate: family-split and apidiff-exposed for each Go module of a repository.
# Writes findings.v1 for the head revision and, given a base, for the base revision as the gate's baseline.
# apidiff reports on head only.
# usage: go-dep-check.sh <repo_dir> <base_ref|""> <head_ref> <out_dir> [<module_dir>...]  (default module: .)
#        an empty base (push, manual run) runs only the checks of the head revision itself
# env:   DEP_GATE_CONFIG       effective gate configuration (`dependency-gate detect`, output config): Go families
#        APIDIFF  tool path (default: $(go env GOPATH)/bin/apidiff)
# out:   <out_dir>/go-library-head.json, <out_dir>/go-library-base.json (with a base)
# exit:  0 findings written; 2 the adapter could not run (the head file says why)
set -uo pipefail
shopt -s extglob
export LC_ALL=C

here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=findings.sh
. "$here/findings.sh"
repo=$(cd "$1" && pwd) base=$2 head=$3 out=$4; shift 4
modules=("${@:-.}")
mkdir -p "$out"; out=$(cd "$out" && pwd)
gobin=$(go env GOPATH)/bin
AD=${APIDIFF:-$gobin/apidiff}
TOOL="go-dep-check ($(go env GOVERSION))"

work=$(mktemp -d) reason="" written="" ran=""
H=$work/head.jsonl B=$work/base.jsonl
: > "$H"; : > "$B"
log() { echo "go-dep-check: $*" >&2; }
fail() { reason=$1; exit 2; }
cleanup() {
  git -C "$repo" worktree remove -f "$work/old" > /dev/null 2>&1; git -C "$repo" worktree remove -f "$work/new" > /dev/null 2>&1
  rm -rf "$work"
}
on_exit "$out/go-library-head.json" go-library
command -v "$AD" > /dev/null || fail "apidiff is not installed: $AD"

git -C "$repo" worktree add -q --detach "$work/new" "$head" || fail "cannot check out $head"
if [[ -n $base ]]; then
  git -C "$repo" worktree add -q --detach "$work/old" "$base" || fail "cannot check out $base"
  behind=$(git -C "$repo" rev-list --count "$(git -C "$repo" merge-base "$base" "$head" 2> /dev/null)..$base" 2> /dev/null)
  [[ ${behind:-0} -gt 0 ]] && log "head is $behind commit(s) behind base: the diff may revert changes from base (CI checks the PR merge commit, where this cannot happen)"
fi
: > "$work/families"
if [[ -n ${DEP_GATE_CONFIG:-} ]]; then
  jq -r '.families[]? | select(.ecosystem == "go") | [.name, .match, .key // "version", .note // ""] | @tsv' "$DEP_GATE_CONFIG" \
    > "$work/families" || fail "cannot read $DEP_GATE_CONFIG"
else
  log "DEP_GATE_CONFIG is not set: module families are not checked"
fi

did() { [[ " $ran " == *" $1 "* ]] || ran+=" $1"; }
# modloc <dir> [<module>]: go.mod location of the module's requirement (default: of the go directive)
modloc() {
  local n
  if [[ -n ${2:-} ]]; then n=$(grep -nE "^[[:space:]]*(require[[:space:]]+)?${2//./\\.}[[:space:]]+v" "$1/go.mod" | head -1 | cut -d: -f1)
  else n=$(grep -nE '^go[[:space:]]' "$1/go.mod" | head -1 | cut -d: -f1); fi
  echo "${mp}go.mod${n:+:$n}"
}
# imports <dir> <own module>: dependency packages the module imports, tests included
imports() {
  (cd "$1" && go list -f '{{join .Imports "\n"}}
{{join .TestImports "\n"}}
{{join .XTestImports "\n"}}' ./... 2> /dev/null) | grep -E '^[^/]+\.[^/]+/' \
    | awk -v own="$2" '$0 != own && index($0, own "/") != 1' | sort -u
}

# --- checks of one revision; the gate compares their findings with the base
# state <dir> <jsonl> <prefix>
state() {
  local d=$1 f=$2 p=$3 name re key note
  (cd "$d" && go mod download) > /dev/null 2>&1
  if ! (cd "$d" && go list -m -f '{{.Path}} {{.Version}}' all 2> "$p.mods.err" | sort > "$p.mods"); then
    fail "go list -m all fails: $(excerpt < "$p.mods.err")"
  fi

  # module families must keep one version, indirect ones included
  if [[ -s $work/families ]]; then
    did family-split
    while IFS=$'\t' read -r name re key note; do
      re="$re" awk '$1 ~ ENVIRON["re"]' "$p.mods" > "$p.fam"
      [[ -s $p.fam ]] || continue
      case $key in  # what has to be equal
        minor) cut -d' ' -f2 "$p.fam" | sed -E 's/^(v[0-9]+\.[0-9]+).*/\1/' ;;
        stable) cut -d' ' -f2 "$p.fam" | grep -v '^v0\.' ;;  # otel: v1 modules share a version, v0 ones do not
        *) cut -d' ' -f2 "$p.fam" ;;
      esac | sort -u > "$p.famv"
      [[ $(wc -l < "$p.famv") -gt 1 ]] \
        && emit "$f" family-split "versions diverge (Renovate does not update indirect dependencies by default): $(awk '{print $1 "@" $2}' "$p.fam" | joinl ', ')" \
          package="$name" note="$note" loc="${mp}go.mod"
    done < "$work/families"
  fi
  return 0
}

# --- checks that compare base and head; report on head
# diffs <old dir> <new dir> <old prefix> <new prefix> <prefix>
diffs() {
  local O=$1 N=$2 po=$3 pn=$4 p=$5 own mo vo vn mn x opkg npkg owner
  own=$(cd "$N" && go list -m)
  did apidiff-exposed
  join "$po.mods" "$pn.mods" | awk '$2 != $3' > "$p.changed"
  join -v1 "$po.mods" "$pn.mods" > "$p.removed"
  join -v2 "$po.mods" "$pn.mods" > "$p.added"
  # a major bump changes the module path (x/v4 -> x/v5): pair added paths with old ones by their stem
  # (the old path may stay in the graph as an indirect dependency, so look at changed modules too)
  { cat "$p.removed"; awk '{print $1, $2}' "$p.changed"; } | while read -r mo vo; do
    while read -r mn vn; do
      [[ ${mn%/v+([0-9])} == "${mo%/v+([0-9])}" ]] && echo "$mo $vo $vn $mn"
    done < "$p.added"
  done > "$p.major"
  { awk '{print $1, $2, $3, $1}' "$p.changed"; cat "$p.major"; } > "$p.pairs"

  # apidiff of the dependency packages this module imports; a major bump is compared across the path change
  imports "$O" "$own" > "$p.imp.old"; imports "$N" "$own" > "$p.imp.new"
  : > "$p.incompat"
  while read -r mo vo vn mn; do
    while read -r opkg; do
      npkg=$mn${opkg#"$mo"}
      grep -qxF -- "$npkg" "$p.imp.new" || continue  # head no longer imports it (e.g. moved to the /vN path)
      # packages of a nested module have their own row
      owner=$(cd "$O" && go list -f '{{.Module.Path}}' "$opkg" 2> /dev/null); [[ -n $owner && $owner != "$mo" ]] && continue
      x=$p.ad_$(tr '/.' '__' <<< "$opkg")
      if ! (cd "$O" && "$AD" -w "$x.old" "$opkg" 2> "$x.err") || ! (cd "$N" && "$AD" -w "$x.new" "$npkg" 2>> "$x.err"); then
        # the API change is unknown: fail closed
        emit "$H" apidiff-exposed "apidiff cannot compare the package, check its API by hand" package="$mn" version="$vn" from="$vo" \
          symbol="$npkg" note="$(head -c 300 "$x.err" | tr '\n' ' ')" loc="$(modloc "$N" "$mn")"
        continue
      fi
      "$AD" -incompatible "$x.old" "$x.new" 2> /dev/null | sed -n 's/^- //p' > "$x.res"
      [[ -s $x.res ]] && echo "$mo $vo $vn $mn $npkg $x.res" >> "$p.incompat"
    done < <(grep -E "^${mo//./\\.}(/|$)" "$p.imp.old")
  done < "$p.pairs"
  [[ -s $p.incompat ]] && apidiff_findings "$N" "$p"
}

# apidiff_findings <new dir> <prefix>: one finding per changed top-level symbol that our code references, one for the rest
apidiff_findings() {
  local N=$1 p=$2 mo vo vn mn npkg res name ids hit ownpkg d check note top n uses
  (cd "$N" && go list -f '{{if not .Standard}}{{.ImportPath}}{{end}}' ./... 2> /dev/null) | grep -vE '/internal(/|$)|/mocks?(/|$)' > "$p.ownpkgs"
  while read -r mo vo vn mn npkg res; do
    name=$(cd "$N" && go list -f '{{.Name}}' "$npkg" 2> /dev/null); [[ -z $name ]] && continue
    # identifiers the package is imported as: its name and any aliases
    ids=$( (echo "$name"; cd "$N" && grep -rhoE "^[[:space:]]*(import[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*[[:space:]]+\"${npkg//./\\.}\"" --include='*.go' . \
      | awk '{print $(NF-1)}') | sort -u | joinl '|')
    # exposed: the package's types appear in the exported API of one of our packages, so consumers see the break
    hit=""
    while read -r ownpkg; do
      (cd "$N" && go list -f '{{join .Imports " "}}' "$ownpkg" 2> /dev/null) | tr ' ' '\n' | grep -qxF -- "$npkg" || continue
      d=$(cd "$N" && go doc -all "$ownpkg" 2> /dev/null | grep -m1 -E "(^|[^A-Za-z0-9_.])($ids)\.[A-Z]" | sed -E 's/^[[:space:]]+//')
      [[ -n $d ]] && { hit="$ownpkg: $d"; break; }
    done < "$p.ownpkgs"
    [[ -n $hit ]] || continue
    check=apidiff-exposed note="types appear in our exported API ($hit)"
    [[ $mo != "$mn" ]] && note="from $mo; $note"

    # where our code references the changed top-level symbols as pkg.Sym; method calls on values are not found
    (cd "$N" && grep -rnoE "(^|[^A-Za-z0-9_.])($ids)\.[A-Z][A-Za-z0-9_]*" --include='*.go' . | grep -v '^\./vendor/') \
      | sed -E 's#^\./##; s#^([^:]+:[0-9]+):.*\.([A-Za-z0-9_]+)$#\2 \1#' | sort -u > "$p.uses"
    # (*Client).Do, Client.Transport, Client -> Client
    sed -E 's/^\(?\*?([A-Za-z0-9_]+).*/\1/' "$res" | paste -d' ' - "$res" > "$p.tops"
    : > "$p.unused"
    while read -r top; do
      if grep -q "^$top " "$p.uses"; then
        n=$(grep -c "^$top " "$p.tops")
        mapfile -t uses < <(grep "^$top " "$p.uses" | head -5 | sed "s#^[^ ]* #loc=$mp#")
        emit "$H" "$check" "$n incompatible change(s): $(grep "^$top " "$p.tops" | cut -d' ' -f2- | head -3 | joinl '; ' | cut -c1-400)" \
          package="$mn" version="$vn" from="$vo" symbol="$name.$top" note="$note" "${uses[@]}"
      else
        grep "^$top " "$p.tops" | cut -d' ' -f2- >> "$p.unused"
      fi
    done < <(cut -d' ' -f1 "$p.tops" | sort -u)
    [[ -s $p.unused ]] \
      && emit "$H" "$check" "$(wc -l < "$p.unused") incompatible change(s) in symbols our code does not reference as $name.<Symbol>, e.g. $(head -3 "$p.unused" | joinl '; ' | cut -c1-400)" \
        package="$mn" version="$vn" from="$vo" symbol="$name.*" note="$note" loc="$(modloc "$N" "$mn")"
  done < "$p.incompat"
}

checked=0 i=0
for m in "${modules[@]}"; do
  i=$((i + 1)) m=${m%/}
  mp=""; [[ $m != . ]] && mp=$m/  # prefix that makes module paths repo-relative
  UNIT=$m
  if [[ -n $base ]] && git -C "$repo" diff --quiet "$base" "$head" -- "${mp}go.mod" "${mp}go.sum"; then
    log "$m: go.mod/go.sum unchanged"; continue
  fi
  [[ -f $work/new/${mp}go.mod ]] || { log "$m: no go.mod at $head"; continue; }
  checked=$((checked + 1))
  log "$m: checking"
  ok=1; state "$work/new/$m" "$H" "$work/m$i.n" || ok=""
  if [[ -n $base && -f $work/old/${mp}go.mod ]]; then
    state "$work/old/$m" "$B" "$work/m$i.o" && [[ -n $ok ]] && diffs "$work/old/$m" "$work/new/$m" "$work/m$i.o" "$work/m$i.n" "$work/m$i"
  fi
done

if [[ $checked == 0 ]]; then
  write_findings "$out/go-library-head.json" go-library head skipped "go.mod/go.sum unchanged" "" < /dev/null
else
  write_findings "$out/go-library-head.json" go-library head ok "" "$ran" < "$H"
  [[ -n $base ]] && write_findings "$out/go-library-base.json" go-library base ok "" "$ran" < "$B"
fi
written=1
log "$(jq -r '"\(.findings | length) finding(s) on head: \([.findings[].check] | group_by(.) | map("\(.[0]) \(length)") | join(", "))"' "$out/go-library-head.json")"
