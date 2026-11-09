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
| join("\u001f")
