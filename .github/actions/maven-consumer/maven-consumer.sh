#!/usr/bin/env bash
# maven-consumers adapter: build one Maven consumer against the library installed at head and, when given, at base.
# usage: maven-consumer.sh <consumer_dir> <head_tarball> <base_tarball|""> <out_dir> <name>
# env:   SETTINGS  settings.xml (GitHub Packages). MAVEN_USERNAME and MAVEN_TOKEN are read by Maven.
# out:   <out_dir>/maven-consumers-<name>-{head,base}.json
# exit:  0 findings written (a failing build is a finding); 2 the adapter could not run
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
consumer=$(cd "$1" && pwd) head_tar=$2 base_tar=$3 out=$4 name=$5
mkdir -p "$out"
file=$out/maven-consumers-${name//\//_}
TOOL="maven-consumer"
log() { echo "maven-consumer: $*" >&2; }

write_findings() {
  # <file> <revision> <status> <reason> <checks> < <jsonl>
  jq -s --arg rev "$2" --arg status "$3" --arg reason "$4" --arg checks "$5" --arg tool "$TOOL" '{
    schema: "dependency-gate-findings/v1",
    adapter: ({name: "maven-consumers", ecosystem: "maven", tool: $tool, status: $status,
               checks: ($checks | split(" ") | map(select(. != "")) | unique)}
      + if $reason != "" then {reason: $reason} else {} end),
    revision: $rev,
    findings: .}' > "$1"
}

emit() {
  # <jsonl> <check> <message>
  jq -nc --arg check "$2" --arg message "$3" --arg package "$name" '{check: $check, message: $message, package: $package}' >> "$1"
}

# one <tarball> <jsonl> <label>: build the consumer on a repo seeded from the runner cache plus this library revision.
# Writes "skip" to <jsonl>.status when the consumer does not reference the installed build.
one() {
  local tar=$1 jsonl=$2 label=$3 repo logf rc
  : > "$jsonl"
  rm -f "$jsonl.status"
  log "$label: $name"
  log "local Maven repository: the runner cache, then the library from this revision. Nothing is published."
  repo=$(mktemp -d)
  if [[ -d ${HOME}/.m2/repository ]]; then cp -a "${HOME}/.m2/repository/." "$repo/"; fi
  tar -xzf "$tar" -C "$repo"
  if ! python3 "$here/align.py" "$consumer" "$repo"; then
    rc=$?
    if [[ $rc == 3 ]]; then
      log "$label: skipped, this consumer does not reference the installed library"
      echo skip > "$jsonl.status"
      return 0
    fi
    log "$label: could not point the consumer at the library build"
    emit "$jsonl" consumer-build "cannot point the consumer at the library build"
    return 0
  fi
  log "$label: mvn verify -DskipITs"
  logf=$repo.log
  set +e
  mvn -B -ntp -s "$SETTINGS" -Dmaven.repo.local="$repo" -f "$consumer/pom.xml" verify -DskipITs > "$logf" 2>&1
  rc=$?
  set -e
  if [[ $rc == 0 ]]; then
    log "$label: built"
    return 0
  fi
  echo "::group::$label maven log"
  tail -n 40 "$logf"
  echo "::endgroup::"
  if grep -qE 'There are test failures|maven-surefire-plugin' "$logf"; then
    log "$label: tests failed"
    emit "$jsonl" consumer-test "tests fail: $(grep -m1 -E 'Tests run:|There are test failures' "$logf" | cut -c1-300)"
  else
    log "$label: build failed"
    emit "$jsonl" consumer-build "build fails: $(grep -m1 -E 'ERROR|FAILURE' "$logf" | cut -c1-300)"
  fi
}

[[ -n ${SETTINGS:-} && -f $SETTINGS ]] || { log "SETTINGS is not a settings.xml"; exit 2; }
[[ -f $head_tar ]] || { log "no head library build: $head_tar"; exit 2; }

log "consumer $name: does it still build against this library"
tmp=$(mktemp -d)
one "$head_tar" "$tmp/head" "head"
if [[ -f $tmp/head.status ]]; then
  write_findings "$file-head.json" head skipped "$name does not use this library build" "" < /dev/null
  exit 0
fi
write_findings "$file-head.json" head ok "" "consumer-build consumer-test" < "$tmp/head"
if [[ -n $base_tar && -f $base_tar ]]; then
  log "base: the same build on the pull request base, so a failure that already exists stays a warning"
  one "$base_tar" "$tmp/base" "base"
  write_findings "$file-base.json" base ok "" "consumer-build consumer-test" < "$tmp/base"
else
  log "no base library build: a failure of this consumer counts as new"
fi
