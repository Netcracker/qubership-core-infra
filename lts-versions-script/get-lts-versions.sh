#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
    echo "Usage: $0 [--branch <branch>] [--format <table|properties|csv>]"
    echo ""
    echo "  --branch   Git branch to inspect (default: current branch)"
    echo "  --format   Output format: table (default), properties, csv"
    echo ""
    echo "Examples:"
    echo "  $0 --branch lts/26.2"
    echo "  $0 --branch lts/26.1 --format properties"
    exit 1
}

BRANCH=""
FORMAT="table"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --branch) BRANCH="$2"; shift 2 ;;
        --format) FORMAT="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

if [[ -z "$BRANCH" ]]; then
    BRANCH=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
fi

declare -A VERSIONS
declare -a ORDER

while IFS= read -r line; do
    release="${line#*prepare release }"
    version="${release##*-}"
    artifact="${release%-$version}"

    # Skip entries where the "artifact" is just a version number (old-style tags)
    [[ "$artifact" =~ ^v?[0-9]+\.[0-9] ]] && continue
    # Skip if artifact and version are the same (no real artifact name)
    [[ "$artifact" == "$version" ]] && continue

    if [[ -z "${VERSIONS[$artifact]+_}" ]]; then
        VERSIONS["$artifact"]="$version"
        ORDER+=("$artifact")
    fi
done < <(git -C "$REPO_ROOT" log --oneline "$BRANCH" | grep "prepare release")

if [[ ${#ORDER[@]} -eq 0 ]]; then
    echo "No releases found on branch '$BRANCH'" >&2
    exit 1
fi

case "$FORMAT" in
    table)
        printf "%-55s %s\n" "ARTIFACT" "VERSION"
        printf "%-55s %s\n" "$(printf '%.0s-' {1..55})" "-------"
        for artifact in "${ORDER[@]}"; do
            printf "%-55s %s\n" "$artifact" "${VERSIONS[$artifact]}"
        done
        ;;
    properties)
        for artifact in "${ORDER[@]}"; do
            key="${artifact//-/.}"
            echo "${key}.version=${VERSIONS[$artifact]}"
        done
        ;;
    csv)
        echo "artifact,version"
        for artifact in "${ORDER[@]}"; do
            echo "${artifact},${VERSIONS[$artifact]}"
        done
        ;;
    *)
        echo "Unknown format: $FORMAT" >&2
        usage
        ;;
esac
