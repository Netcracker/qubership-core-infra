#!/usr/bin/env bash
# go-consumers adapter of the dependency gate: builds one consumer Go module against the library at base and at
# head (replace + tidy, so MVS raises the library's own dependencies like a real bump) and writes findings.v1 for
# both revisions: build and tests (compile, test-binary init) per revision, new go vet findings on head only.
# With tests=true it also runs the consumer's tests on head (gotestsum, failed tests rerun up to twice) and the
# tests that still fail once more on base.
# usage: consumer-check.sh <consumer_module_dir> <lib_base_dir> <lib_head_dir> <out_dir> <consumer_name> [<tests>]
# The consumer dir is modified temporarily and restored (git checkout/clean) at the end.
# out:   <out_dir>/go-consumers-<consumer_name>-{head,base}.json, "/" in the name replaced by "_"
# exit:  0 findings written; 2 the check could not run (the head file says why)
set -uo pipefail
export LC_ALL=C

here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=findings.sh
. "$here/findings.sh"
cdir=$(cd "$1" && pwd) lb=$(cd "$2" && pwd) lh=$(cd "$3" && pwd) out=$4 name=${5%/.} tests=${6:-false}
mkdir -p "$out"; out=$(cd "$out" && pwd)
file=$out/go-consumers-${name//\//_}
TOOL="go-dep-check consumer-check ($(go env GOVERSION))"
t=$(mktemp -d) reason="" written="" US=$'\x1f'

restore() { git -C "$cdir" checkout -q -- . ; git -C "$cdir" clean -fdq -- . ; }
cleanup() { restore; rm -rf "$t"; }
on_exit "$file-head.json" go-consumers "$name: "
skip() { write_findings "$file-head.json" go-consumers head skipped "$name: $1" "" < /dev/null; written=1; exit 0; }

if [[ ! -f $lh/go.mod ]] || ! mod=$(cd "$lh" && go list -m); then reason="no library module in $3"; exit 2; fi
if [[ $tests == true ]] && ! command -v gotestsum > /dev/null; then reason="gotestsum is not installed"; exit 2; fi
# quick filter, then the exact one: is the library compiled into this module (also transitively)? A go list that
# fails (network, broken go.mod) leaves the consumer unchecked, which is an error, not a skip.
cut -d' ' -f1 "$cdir/go.sum" 2> /dev/null | grep -qxF -- "$mod" || skip "not a consumer of $mod"
if ! (cd "$cdir" && go list -deps -test -f '{{with .Module}}{{.Path}}{{end}}' ./...) > "$t/deps" 2> "$t/deps.err"; then
  reason="go list failed: $(grep -v '^go: downloading' "$t/deps.err" | head -3 | cut -c1-300 | joinl '; ')"
  exit 2
fi
grep -qxF -- "$mod" "$t/deps" || skip "not a consumer of $mod (only in go.sum)"
kind=transitive
[[ -n $(cd "$cdir" && go list -m -f '{{if not .Indirect}}{{.Path}}{{end}}' "$mod" 2> /dev/null) ]] && kind=direct

use() {  # use <lib_dir>: the consumer with the library at that revision
  restore
  (cd "$cdir" && go mod edit -replace "$mod=$1" && go mod tidy)
}
check() {  # check <lib_dir> <prefix>
  local d=$1 p=$2
  # a revision that does not tidy has no MVS changes, vet or test output: on head every vet finding is new
  : > "$p.mvs"; : > "$p.vet"; : > "$p.test"
  if ! use "$d" > "$p.build" 2>&1; then echo tidy > "$p.rc"; return; fi
  (cd "$cdir" && git diff -U0 go.mod | grep -E '^[-+][[:space:]]*[a-z]' | grep -vF -- "$mod") > "$p.mvs"
  (cd "$cdir" && go build -o /dev/null ./...) > "$p.build" 2>&1; echo $? > "$p.rc"
  (cd "$cdir" && go vet ./...) > "$p.vet" 2>&1
  (cd "$cdir" && go test -count=1 -run '^$' ./... 2>&1 | grep -vE '^(ok|\?|go: downloading)[[:space:]]|^FAIL$') > "$p.test"
}

# gotestsum --jsonfile → one line per top-level test that failed (a failed subtest fails its parent; the last run
# decides between consumer-test and consumer-test-flaky) or per package that failed without a failed test:
# check, package, test (empty for a package), output excerpt, separated by \x1f
# shellcheck disable=SC2016
results='
  def excerpt: [.[].Output // empty | select(test("^\\s*(=== |--- )|^\\s*\\S+\\.go:[0-9]+:\\s*$") | not)
    | select(test("\\.go:[0-9]+|panic|[Ee]rror|timed out|expected|actual")) | gsub("/[^\\s:]+/"; "") | gsub("\\s+"; " ")
    | ltrimstr(" ") | rtrimstr(" ") | .[:300]] | reduce .[] as $l ([]; if any(.[]; . == $l) then . else . + [$l] end)
    | .[:4] | join("; ");
  map(select(.Package)) | group_by(.Package)[] | . as $p
  | (map(select(.Test and (.Test | contains("/") | not) and (.Action == "pass" or .Action == "fail")))
     | group_by(.Test) | map(select(any(.Action == "fail")))) as $failed
  | if $failed != [] then
      $failed[] | .[0].Test as $t
      | [if last.Action == "fail" then "consumer-test" else "consumer-test-flaky" end, $p[0].Package, $t,
         ($p | map(select(.Test == $t or (.Test // "" | startswith($t + "/")))) | excerpt)]
    elif any($p[]; .Action == "fail") then ["consumer-test", $p[0].Package, "", ($p | map(select(.Test == null)) | excerpt)]
    else empty end
  | join("\u001f")'
run_tests() {  # run_tests <prefix> <packages> [<go test flags>...]
  local p=$1 pkgs=$2; shift 2
  (cd "$cdir" && gotestsum --jsonfile "$p.json" --rerun-fails=2 --packages "$pkgs" -- -count=1 -timeout 15m "$@") >&2
  [[ -s $p.json ]] && jq -rs "$results" "$p.json" > "$p.tests"
}

check "$lb" "$t/b"; check "$lh" "$t/h"
: > "$t/b.jsonl"; : > "$t/h.jsonl"; : > "$t/b.tests"; : > "$t/h.tests"
ran_b="" ran_h=""
# tests that do not build are already consumer-build findings
if [[ $tests == true && $(cat "$t/h.rc") == 0 && ! -s $t/h.test ]]; then
  run_tests "$t/h" ./... || { reason="gotestsum wrote no test results, see the job log"; exit 2; }
  ran_h=1
  failed=$(grep "^consumer-test$US" "$t/h.tests" | cut -d "$US" -f 2,3)
  if [[ -n $failed && $(cat "$t/b.rc") == 0 ]] && use "$lb" > "$t/b.use" 2>&1; then
    names=$(cut -d "$US" -f 2 <<< "$failed" | sort -u)
    # a package that failed as a whole runs whole
    run=()
    grep -qx '' <<< "$names" || run=(-run "^($(joinl '|' <<< "$names"))\$")
    run_tests "$t/b" "$(cut -d "$US" -f 1 <<< "$failed" | sort -u | joinl ' ')" "${run[@]}" && ran_b=1
  fi
fi

# errors of the consumer and of the library, file names without their directories
short() { excerpt | sed -E 's#^[^[:space:]]*/##' | joinl '; '; }
mvs=$(diff <(sort "$t/b.mvs") <(sort "$t/h.mvs") | sed -n 's/^> //p' | tr -s '[:space:]' ' ' | head -8 | joinl '; ')
note="$kind consumer${mvs:+; MVS also changes: $mvs}"
# the aspect is the symbol, so a build broken on head is not hidden by tests already failing on base
for r in b h; do
  [[ $r == b ]] && what="the library at base" || what="this change"
  [[ $(cat "$t/$r.rc") != 0 ]] \
    && emit "$t/$r.jsonl" consumer-build "does not build with $what: $(short < "$t/$r.build")" package="$name" symbol=build note="$note"
  [[ -s $t/$r.test ]] \
    && emit "$t/$r.jsonl" consumer-build "tests do not compile or test binaries fail to start with $what: $(short < "$t/$r.test")" \
      package="$name" symbol=tests note="$note"
  while IFS=$US read -r check pkg test excerpt; do
    [[ $check == consumer-test ]] && msg="${test:-the package} fails with $what" \
      || msg="${test:-the package} failed and passed on a rerun with $what"
    emit "$t/$r.jsonl" "$check" "$msg${excerpt:+: $excerpt}" package="$name" symbol="$pkg${test:+.$test}" note="$note"
  done < "$t/$r.tests"
done
nv=$(newvet "$t/b.vet" "$t/h.vet")
[[ -n $nv ]] && emit "$t/h.jsonl" consumer-build "new go vet findings with this change: $(short <<< "$nv")" package="$name" symbol=vet note="$note"

testchecks=" consumer-test consumer-test-flaky"
write_findings "$file-head.json" go-consumers head ok "" "consumer-build${ran_h:+$testchecks}" < "$t/h.jsonl"
write_findings "$file-base.json" go-consumers base ok "" "consumer-build${ran_b:+$testchecks}" < "$t/b.jsonl"
written=1
echo "$name: $kind consumer, $(jq '.findings | length' "$file-head.json") finding(s) on head, $(jq '.findings | length' "$file-base.json") on base" >&2
