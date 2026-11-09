# shellcheck shell=bash
# Writes findings.v1 files (tools/dependency-gate/schemas/findings.v1.json) for the go-dep-check scripts.
# Sourced; needs jq.

# on_exit <head file> <adapter> <ecosystem> [<reason prefix>]
# An exit before $written is set writes an error findings file for head with $reason, then runs cleanup.
on_exit() {
  _head=$1 _adapter=$2 _ecosystem=$3 _prefix=${4:-}
  trap _finish EXIT
  trap 'reason=interrupted; exit 2' INT TERM
}
_finish() {
  local rc=$?
  if [[ -z ${written:-} ]]; then
    write_findings "$_head" "$_adapter" "$_ecosystem" head error "$_prefix${reason:-$(basename "$0") exited with code $rc}" "" < /dev/null
    rc=2
  fi
  cleanup
  exit "$rc"
}

# emit <jsonl> <check> <message> [key=value ...]: appends one finding. Keys: package, version, from, symbol, vuln,
# note, loc=<repo-relative file>[:<line>] (repeatable, unit from $UNIT). Empty values are left out.
emit() {
  local out=$1; shift
  jq -nc --arg unit "${UNIT:-}" '
    $ARGS.positional as $a
    | reduce ($a[2:][] | capture("^(?<k>[a-z]+)=(?<v>.*)$"; "p")) as $p ({check: $a[0], message: $a[1]};
        if $p.v == "" then .
        elif $p.k == "loc" then
          .locations += [$p.v | capture("^(?<file>.+?)(:(?<line>[0-9]+))?$")
            | {file} + (if .line then {line: (.line | tonumber)} else {} end) + (if $unit != "" then {unit: $unit} else {} end)]
        else .[$p.k] = $p.v end)' --args "$@" >> "$out"
}

# write_findings <file> <adapter> <ecosystem> <revision> <status> <reason> <checks> < <jsonl>
write_findings() {
  jq -s --arg name "$2" --arg eco "$3" --arg rev "$4" --arg status "$5" --arg reason "$6" --arg checks "$7" --arg tool "${TOOL:-go-dep-check}" '{
    schema: "dependency-gate-findings/v1",
    adapter: ({name: $name, ecosystem: $eco, tool: $tool, status: $status,
               checks: ($checks | split(" ") | map(select(. != "")) | unique)}
      + if $reason != "" then {reason: $reason} else {} end),
    revision: $rev,
    findings: .}' > "$1"
}

# excerpt [n]: the first n (default 3) error lines of stdin
excerpt() {
  grep -m "${1:-3}" -E '\.go:[0-9]+|panic|error|cannot|undefined|FAIL' | cut -c1-300
}

# joinl <separator> < lines: one line
joinl() {
  awk -v s="$1" '{printf "%s%s", (NR > 1 ? s : ""), $0}'
}

# newvet <old vet output> <new vet output>: the new go vet findings, compared by file and message without
# :LINE:COL, so code that only moved is not new; unchanged lines are matched first
newvet() {
  awk 'function key(s) { sub(/:[0-9]+(:[0-9]+)?:/, ":", s); return s }
    /^(#|go: downloading)/ { next }
    FILENAME == ARGV[1] { old[$0]++; moved[key($0)]++; next }
    old[$0] > 0 { old[$0]--; moved[key($0)]--; next }
    { rest[++n] = $0 }
    END { for (i = 1; i <= n; i++) if (moved[key(rest[i])]-- <= 0) print rest[i] }' "$1" <(sort -u "$2")
}

