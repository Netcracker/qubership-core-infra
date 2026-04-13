#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT="${1:-versions-report.html}"
GIT_REPO="${2:-$REPO_ROOT}"

# Collect all LTS branches (local + remote, deduplicated)
mapfile -t BRANCHES < <(
    git -C "$GIT_REPO" branch -a --list '*lts/*' \
        | sed 's|.*remotes/origin/||; s|^[* ]*||' \
        | sort -uV
)

if [[ ${#BRANCHES[@]} -eq 0 ]]; then
    echo "No lts/* branches found." >&2
    exit 1
fi

# For each branch collect: artifact -> version (latest)
declare -A DATA   # DATA[branch:artifact]=version
declare -A ALL_ARTIFACTS  # used as ordered set

get_versions() {
    local branch="$1"
    declare -A branch_seen

    while IFS= read -r line; do
        release="${line#*prepare release }"
        version="${release##*-}"
        artifact="${release%-$version}"

        [[ "$artifact" =~ ^v?[0-9]+\.[0-9] ]] && continue
        [[ "$artifact" == "$version" ]] && continue

        if [[ -z "${branch_seen[$artifact]+_}" ]]; then
            branch_seen["$artifact"]=1
            DATA["${branch}:${artifact}"]="$version"
            ALL_ARTIFACTS["$artifact"]=1
        fi
    done < <({ git -C "$GIT_REPO" log --oneline "origin/${branch}" 2>/dev/null \
              || git -C "$GIT_REPO" log --oneline "${branch}"; } \
              | grep "prepare release")
}

echo "Collecting versions from branches: ${BRANCHES[*]}"
for branch in "${BRANCHES[@]}"; do
    get_versions "$branch"
done

# Order artifacts: stable sort by name
mapfile -t ARTIFACT_LIST < <(printf '%s\n' "${!ALL_ARTIFACTS[@]}" | sort)

GENERATED_AT="$(date -u '+%Y-%m-%d %H:%M UTC')"

cat > "$OUTPUT" <<HTML
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Qubership Core Java Libs — LTS Versions</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
           font-size: 14px; background: #f6f8fa; color: #24292f; }
    header { background: #24292f; color: #fff; padding: 16px 24px;
             display: flex; align-items: center; justify-content: space-between; }
    header h1 { font-size: 18px; font-weight: 600; }
    header span { font-size: 12px; opacity: 0.6; }
    .container { padding: 24px; overflow-x: auto; }
    table { border-collapse: collapse; width: 100%; background: #fff;
            border: 1px solid #d0d7de; border-radius: 6px; overflow: hidden; }
    th { background: #f6f8fa; border-bottom: 1px solid #d0d7de;
         padding: 10px 16px; text-align: left; font-weight: 600;
         white-space: nowrap; position: sticky; top: 0; z-index: 1; }
    th.branch { text-align: center; }
    td { padding: 8px 16px; border-bottom: 1px solid #eaeef2; }
    tr:last-child td { border-bottom: none; }
    tr:hover td { background: #f6f8fa; }
    td.version { text-align: center; font-family: 'SFMono-Regular', Consolas, monospace;
                 font-size: 13px; }
    td.version span { background: #ddf4ff; color: #0969da; border-radius: 12px;
                      padding: 2px 10px; display: inline-block; }
    td.missing { text-align: center; color: #8c959f; font-size: 12px; }
    .filter-bar { padding: 12px 24px 0; }
    .filter-bar input { padding: 6px 12px; border: 1px solid #d0d7de; border-radius: 6px;
                        font-size: 14px; width: 300px; outline: none; }
    .filter-bar input:focus { border-color: #0969da; box-shadow: 0 0 0 3px rgba(9,105,218,.15); }
  </style>
</head>
<body>
<header>
  <h1>Qubership Core Java Libs — LTS Versions</h1>
  <span>Generated: ${GENERATED_AT}</span>
</header>
<div class="filter-bar">
  <input type="text" id="filter" placeholder="Filter artifacts..." oninput="filterTable(this.value)">
</div>
<div class="container">
  <table id="report">
    <thead>
      <tr>
        <th>Artifact</th>
HTML

for branch in "${BRANCHES[@]}"; do
    echo "        <th class=\"branch\">${branch}</th>" >> "$OUTPUT"
done

cat >> "$OUTPUT" <<HTML
      </tr>
    </thead>
    <tbody>
HTML

for artifact in "${ARTIFACT_LIST[@]}"; do
    echo "      <tr>" >> "$OUTPUT"
    echo "        <td>${artifact}</td>" >> "$OUTPUT"
    for branch in "${BRANCHES[@]}"; do
        key="${branch}:${artifact}"
        if [[ -n "${DATA[$key]+_}" ]]; then
            echo "        <td class=\"version\"><span>${DATA[$key]}</span></td>" >> "$OUTPUT"
        else
            echo "        <td class=\"missing\">—</td>" >> "$OUTPUT"
        fi
    done
    echo "      </tr>" >> "$OUTPUT"
done

cat >> "$OUTPUT" <<HTML
    </tbody>
  </table>
</div>
<script>
  function filterTable(q) {
    q = q.toLowerCase();
    document.querySelectorAll('#report tbody tr').forEach(tr => {
      tr.style.display = tr.cells[0].textContent.toLowerCase().includes(q) ? '' : 'none';
    });
  }
</script>
</body>
</html>
HTML

echo "Report written to: $OUTPUT"
