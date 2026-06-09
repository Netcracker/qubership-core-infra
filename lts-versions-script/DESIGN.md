# LTS Versions Report — Design

## What it does

Generates a single HTML page with two tabs — **Java** and **Go** — showing LTS branch versions and main-branch versions for all configured repositories.

- **Java**: reads `pom.xml` files from local clones; LTS version = `X.Y.(N-1)` derived from `X.Y.N-SNAPSHOT`; main shows the raw SNAPSHOT string.
- **Go**: clones repositories (blobless) and queries git tags; LTS version = latest `vX.Y.Z` tag reachable from the LTS branch; main shows the latest tag reachable from `main`/`master`.

## Files

```
lts-versions-script/
├── lts-versions.py       # single entry point
├── java-repos.txt        # Netcracker/repo lines — Java repositories
├── go-repos.txt          # Netcracker/repo lines — Go repositories
├── report.html.j2        # top-level Jinja2 template (tabs shell)
├── java-table.html.j2    # Java tab partial
├── go-table.html.j2      # Go tab partial
└── DESIGN.md
```

## CLI

```
lts-versions.py [output.html]
  --java-repos <file>       text file with Netcracker/repo lines
  --java-workspace <path>   directory where Java repos are checked out (default: .)
  --go-repos <file>         text file with Netcracker/repo lines
```

At least one of `--java-repos` / `--go-repos` must be provided.

## Repository configuration

Both `java-repos.txt` and `go-repos.txt` share the same format:

```
# one Netcracker/repo-name per line; blank lines and # comments are ignored
Netcracker/qubership-core-java-libs
Netcracker/qubership-core-core-operator
```

Adding a repository: append one line to the appropriate file.

## Java — repository types (auto-detected)

- **Monorepo** (`qubership-core-java-libs`): top-level subdirectory `pom.xml` files each carry their own `<version>`. Each subdirectory is an independent module.
- **Single-product** (`qubership-core-core-operator`, `qubership-core-config-server`): version from root `pom.xml`; artifacts from first-level sub-module `pom.xml` files. Module name = repository directory name.

## Java — git access (2 processes per branch per repo)

```
git ls-tree -r --name-only <ref>   →  list all file paths
git archive <ref> <pom_paths>      →  tar stream with all pom.xml at once
```

## Java — pom.xml parsing (plain text, no XML library)

Tags appear one per line as `<tag>value</tag>`.

- **`artifactId`**: first occurrence outside `<parent>` block.
- **`version`** (module root only): first occurrence outside `<parent>` block.
- **`groupId`**: scan until `<dependencies>` / `<build>` / `<profiles>` / `<reporting>` — first literal value (no `${}`); fall back to `<groupId>` inside `<parent>`.

## Java — version computation

LTS branch `pom.xml` always contains `X.Y.N-SNAPSHOT` with `N ≥ 1`.  
Latest release = `X.Y.(N-1)`.  
`main` SNAPSHOT is shown as-is (e.g. `X.Y.N-SNAPSHOT`).

## Go — cloning strategy

The script clones all Go repositories itself using `GH_TOKEN` from the environment:

```
git clone --filter=blob:none --no-checkout https://x-token-auth:{token}@github.com/{org}/{repo}.git
git fetch --all --tags --prune
```

Blobs are excluded (`--filter=blob:none`) because only git history and tags are needed.
All repositories are cloned in parallel via `ThreadPoolExecutor(max_workers=8)`.
Clones are created in a temporary directory that is deleted after data collection.

## Go — version computation

```
git tag --merged origin/lts/X.Y --sort=-version:refname
```

The first tag matching `^v[0-9]` is taken as the latest release on that branch.

For `main`: the same query against `origin/main` (falls back to `origin/master`).

## Go — module path

Read from `go.mod` at `HEAD` via `git show HEAD:go.mod`, taking the `module` directive value. This is what is displayed in the "Module path" column of the Go tab.

## Go — multi-module repositories

Not currently supported. If a repository contains multiple `go.mod` files in subdirectories, only the root module path is read and only root-level `vX.Y.Z` tags are matched. Subdirectory module tags (`subdir/vX.Y.Z`) are ignored.

## Branch union

LTS branches are the union of `lts/*` branches across all repositories of the same type. If a repository does not have a particular branch, its row shows `—` in that column.

## HTML report structure

The page uses a pure-CSS tab switcher (radio inputs + sibling selectors, no JavaScript).

**Java tab:**

| Module | Artifact (`groupId:artifactId`) | lts/25.3 | … | main (SNAPSHOT) |
|--------|---------------------------------|----------|---|-----------------|

- One row per artifact; module cell uses `rowspan`.
- Each LTS column header includes a `↓ export` download link.

**Go tab:**

| Module (repo name) | Module path | lts/25.3 | … | main (latest tag) |
|--------------------|-------------|----------|---|-------------------|

- One row per repository.

## Export files (Java only)

Written alongside `index.html`. One file per LTS branch (e.g. `lts_25.3.txt`) plus `main_snapshot.txt`.  
Format: one `groupId:artifactId:version` line per artifact, only modules present on that branch.

## Automation

GitHub Actions workflow (`.github/workflows/lts-versions-report.yml`):

- Triggers: nightly (`0 3 * * *`) and `workflow_dispatch`.
- Checks out the `infra` repository (scripts + config files).
- Clones Java repositories in a shell loop reading `java-repos.txt` (full clone, needed to read `pom.xml` from disk for monorepo detection).
- The script clones Go repositories internally, reading `go-repos.txt`.
- Installs `jinja2`, runs the script, deploys `_site/` to `gh-pages` under `lts-versions/` (`keep_files: true`).
- `GH_TOKEN` / `MAVEN_RELEASE_DEV_TOKEN` secret is used for all private repository access.
