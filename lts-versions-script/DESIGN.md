# LTS Versions Report — Design

## What it does

For each `lts/*` branch: reads every `pom.xml` recursively, extracts `groupId`, `artifactId`, and the module version. Also reads SNAPSHOT versions from `main`. Produces an HTML report and per-branch export files.

## Files

```
lts-versions-script/
├── lts-versions.py   # single entry point
├── report.html.j2    # Jinja2 HTML template
└── DESIGN.md
```

## CLI

```
lts-versions.py [output.html] [--repo <monorepo-path>]
```

`--repo` overrides the default monorepo root (two directories above the script). Used when the script lives in a service repo and the monorepo is checked out separately.

## Git access — 2 processes per branch

```
git ls-tree -r --name-only <ref>   →  list all file paths
git archive <ref> <pom_paths>      →  tar stream, all pom.xml at once
```

`pom_paths` = paths ending with `pom.xml` that contain at least one `/` (excludes repo-root aggregator).

## pom.xml parsing — plain text, no XML library

Tags appear one per line as `<tag>value</tag>`. Parsing rules:

- **`artifactId`**: first occurrence outside `<parent>` block.
- **`version`** (module root only): first occurrence outside `<parent>` block.
- **`groupId`**: scan top of file until `<dependencies>`, `<build>`, `<profiles>`, or `<reporting>` is encountered — take first literal value (no `${}`). If not found, fall back to `<groupId>` inside `<parent>`.

## Version computation

LTS branch pom.xml always contains `X.Y.N-SNAPSHOT` with `N ≥ 1`. Latest release = `X.Y.(N-1)`.

`main` pom.xml SNAPSHOT version is shown as-is (e.g. `X.Y.N-SNAPSHOT`).

## HTML report structure

| Module | Artifact (`groupId:artifactId`) | lts/25.3 | lts/26.2 | … | main |
|--------|---------------------------------|----------|----------|---|------|

- One row per artifact (all pom.xml recursively, sorted alphabetically within module).
- Module cell uses `rowspan` — same version for all artifacts in a module.
- LTS branch columns show released versions with a blue badge.
- `main` column is last, shows SNAPSHOT versions with a yellow badge.
- Each column header has a download link (`↓ export`) pointing to a pre-generated `.txt` file.

## Export files

Written to the same directory as `index.html`. One file per branch (e.g. `lts_26.2.txt`) plus `main_snapshot.txt`. Format: one `groupId:artifactId:version` per line, only modules present on that branch.

## Automation

GitHub Actions workflow (`.github/workflows/lts-versions-report.yml`):
- Triggers: nightly (`0 3 * * *`) and `workflow_dispatch`.
- Checks out `Netcracker/qubership-core-java-libs` with full history and all branches.
- Installs `jinja2`, runs the script, deploys `_site/` to `gh-pages` branch under `lts-versions/` path (`keep_files: true`).
