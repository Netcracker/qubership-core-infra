# PR Report — Design

## Overview

Static HTML report with open Pull Requests across Netcracker repositories, published to GitHub Pages via a daily workflow.

## Repository discovery

Repositories are fetched from GitHub Search API using the `qubership-core` topic. A file-based exclusion list (`exclude-repos.txt`) filters out unwanted repositories before fetching their PRs.

## Data fetching

The script runs in two parallel phases using `ThreadPoolExecutor`:

1. **PRs** — fetch all open (or filtered by `--state`) PRs for each discovered repository in parallel.
2. **Checks** — fetch CI check runs per PR head commit SHA in parallel.

All API requests go through a shared `api_get()` function with retry logic: 3 attempts with delays of 0.5 → 1 → 2 seconds, respecting the `Retry-After` header on 429/403 responses.

## Classification

Business logic lives in Python, not in the template:

| Field | Logic |
|---|---|
| `is_bot` | author login ∈ `BOTS` set |
| `lang` | `go` / `java` / `other` based on repo topics |
| `repo_type` | `lib` (topic `lib`) / `service` (topic `cloud-core`) / `other` |

These fields are written into `data-*` attributes on each table row. The template and JS only read them.

## Check run aggregation

For each PR, check runs are aggregated into:
- **status**: `success` / `failure` / `pending` / `none`
- **counts**: passed, failed, pending, total
- **failed_runs**: list of `{name, url}` for failed checks

## HTML report

A self-contained single-file HTML page rendered via Jinja2. The table has 9 columns: Repository, Pull Request, Author, Status, Labels, Base branch, Created, Updated, Checks.

**Filtering (client-side, no backend):**
- Free-text search across repo, title, author, labels
- Repository selector
- PR state: Open / Draft / Closed
- Author type: All / Bot / Developer
- Language: All / Go / Java / Other
- Repo type: All / Lib / Service / Other
- "Show All" button resets all filters at once

**Checks column**: clickable indicator (●) linking to the PR checks page + individual ✗ links per failed check with the check name in a tooltip.

## Deployment

GitHub Actions workflow (`.github/workflows/pr-report.yml`):
- Schedule: daily at 04:00 UTC
- `workflow_dispatch` with `state` input (open / closed / all)
- Publishes to `gh-pages` branch under `pr-report/` subdirectory
- `keep_files: true` preserves other pages (e.g. `lts-versions/`)

## Local run

```bash
GITHUB_TOKEN=ghp_xxx python3 pr-report/pr-report.py output.html
```

Options: `--state open|closed|all`, `--workers N` (default 8), `--exclude path/to/file.txt`.
