#!/usr/bin/env bash
# maven-consumers adapter: mvn verify for one revision whose poms already point at the library.
# use-java-libs installs that library and rewrites the poms. This script only runs the build and
# writes findings.v1.
# usage: maven-consumer.sh <head|base> <consumer_dir> <maven_repo|skip> <out_dir> <name>
# env:   SETTINGS  settings.xml (GitHub Packages). MAVEN_USERNAME and MAVEN_TOKEN are read by Maven.
# out:   <out_dir>/maven-consumers-<name>-<revision>.json
#        head + skip writes a skipped head file and does not run Maven
#        GITHUB_OUTPUT passed=true when mvn verify succeeded, false when it failed
# exit:  0 findings written (a failing build is a finding); 2 the adapter could not run
set -uo pipefail
revision=$1
consumer=$(cd "$2" && pwd)
repo=$3
out=$4
name=$5
mkdir -p "$out"
file=$out/maven-consumers-${name//\//_}-${revision}.json
TOOL="maven-consumer"
log() { echo "maven-consumer: $*" >&2; }
set_passed() { [[ -n ${GITHUB_OUTPUT:-} ]] && echo "passed=$1" >> "$GITHUB_OUTPUT"; }

write_findings() {
  # <status> <reason> <checks> < <jsonl>
  jq -s --arg rev "$revision" --arg status "$1" --arg reason "$2" --arg checks "$3" --arg tool "$TOOL" '{
    schema: "dependency-gate-findings/v1",
    adapter: ({name: "maven-consumers", ecosystem: "maven", tool: $tool, status: $status,
               checks: ($checks | split(" ") | map(select(. != "")) | unique)}
      + if $reason != "" then {reason: $reason} else {} end),
    revision: $rev,
    findings: .}' > "$file"
}

emit() {
  # <jsonl> <check> <message>
  jq -nc --arg check "$2" --arg message "$3" --arg package "$name" '{check: $check, message: $message, package: $package}' >> "$1"
}

[[ -n ${SETTINGS:-} && -f $SETTINGS ]] || { log "SETTINGS is not a settings.xml"; exit 2; }
[[ $revision == head || $revision == base ]] || { log "revision must be head or base"; exit 2; }

if [[ $repo == skip || -z $repo ]]; then
  if [[ $revision == head ]]; then
    log "head: skipped, $name does not reference the installed library"
    write_findings skipped "$name does not use this library build" "" < /dev/null
  else
    log "no base library build: a failure of this consumer counts as new"
  fi
  exit 0
fi
[[ -d $repo ]] || { log "maven repository not found: $repo"; exit 2; }

log "$revision: $name"
log "$revision: mvn verify -DskipITs"
logf=$(mktemp)
jsonl=$(mktemp)
set +e
mvn -B -ntp -s "$SETTINGS" -Dmaven.repo.local="$repo" -f "$consumer/pom.xml" verify -DskipITs > "$logf" 2>&1
rc=$?
set -e
if [[ $rc == 0 ]]; then
  log "$revision: built"
  write_findings ok "" "consumer-build consumer-test" < "$jsonl"
  set_passed true
  exit 0
fi
echo "::group::$revision maven log"
tail -n 40 "$logf"
echo "::endgroup::"
if grep -qE 'There are test failures|maven-surefire-plugin' "$logf"; then
  log "$revision: tests failed"
  emit "$jsonl" consumer-test "tests fail: $(grep -m1 -E 'Tests run:|There are test failures' "$logf" | cut -c1-300)"
else
  log "$revision: build failed"
  emit "$jsonl" consumer-build "build fails: $(grep -m1 -E 'ERROR|FAILURE' "$logf" | cut -c1-300)"
fi
write_findings ok "" "consumer-build consumer-test" < "$jsonl"
set_passed false
