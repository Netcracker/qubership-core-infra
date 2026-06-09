# LTS Versions Report — Design

## What it does

Generates a single HTML page with four tabs — **Java Libs**, **Java Services**, **Go Libs**, **Go Services** — showing LTS branch versions and main-branch versions for all configured repositories.

- **Java**: reads `pom.xml` files from local clones; LTS version = `X.Y.(N-1)` derived from `X.Y.N-SNAPSHOT`; main shows the raw SNAPSHOT string.
- **Go**: clones repositories (blobless) and queries git tags; LTS version = latest `vX.Y.Z` tag reachable from the LTS branch; main shows the latest tag reachable from `main`/`master`.

## Files

```
lts-versions-script/
├── lts-versions.py           # single entry point
├── java-libs-repos.txt       # Netcracker/repo lines — Java lib repos (monorepo)
├── java-services-repos.txt   # Netcracker/repo lines — Java service repos
├── go-libs-repos.txt         # Netcracker/repo lines — Go lib repos
├── go-services-repos.txt     # Netcracker/repo lines — Go service repos
├── report.html.j2            # top-level Jinja2 template (tabs shell)
├── java-table.html.j2        # Java tab partial
├── go-table.html.j2          # Go tab partial
└── DESIGN.md
```

## CLI

```
lts-versions.py [output.html]
  --java-libs-repos <file>      text file with Netcracker/repo lines (Java libs)
  --java-services-repos <file>  text file with Netcracker/repo lines (Java services)
  --java-workspace <path>       directory where Java repos are checked out (default: .)
  --go-libs-repos <file>        text file with Netcracker/repo lines (Go libs)
  --go-services-repos <file>    text file with Netcracker/repo lines (Go services)
```

At least one argument must be provided. All four arguments may be passed together to render all tabs.

## Repository configuration

All four repo files share the same format:

```
# one Netcracker/repo-name per line; blank lines and # comments are ignored
Netcracker/qubership-core-java-libs
Netcracker/qubership-core-core-operator
```

Adding a repository: append one line to the appropriate file.

## Java — repository types

- **Monorepo** (`java-libs-repos.txt`): top-level subdirectory `pom.xml` files each carry their own `<version>`. Each subdirectory is an independent module.
- **Single-product** (`java-services-repos.txt`): version from root `pom.xml`; artifacts from first-level sub-module `pom.xml` files. Module name = repository directory name.

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
```

Blobs are excluded (`--filter=blob:none`) because only git history and tags are needed.
All repositories within a single `collect_go` call are cloned in parallel via `ThreadPoolExecutor(max_workers=8)`.
Clones are created in a temporary directory that is deleted after data collection.

## Go — version computation

```
git tag --merged origin/lts/X.Y --sort=-version:refname
```

The first tag matching `^v[0-9]` is taken as the latest release on that branch.

For `main`: the same query against `origin/main` (falls back to `origin/master`).

## Go — module path

Read from `go.mod` at the tip of `main`/`master` via `git show <ref>:go.mod`, taking the `module` directive value. This is what is displayed in the "Module path" column.

## Go — multi-module repositories

Supported. All `go.mod` files in the repository are discovered via `git ls-tree`. For a module in subdirectory `subdir/`, the script matches tags of the form `subdir/vX.Y.Z` and strips the prefix before displaying.

## Branch union

LTS branches are the union of `lts/*` branches across all repositories within the same tab. If a repository does not have a particular branch, its row shows `—` in that column.

## HTML report structure

The page uses a pure-CSS tab switcher (radio inputs + sibling selectors, no JavaScript). Tabs with no data are not rendered.

**Java Libs / Java Services tabs:**

| Module | Artifact (`groupId:artifactId`) | lts/25.3 | … | main (SNAPSHOT) |
|--------|---------------------------------|----------|---|-----------------|

- One row per artifact; module cell uses `rowspan`.
- Each LTS column header includes a `↓ export` download link.

**Go Libs / Go Services tabs:**

| Repository | Module path | lts/25.3 | … | main (latest tag) |
|------------|-------------|----------|---|-------------------|

- One row per Go module; repository cell uses `rowspan`.
- Each LTS column header includes a `↓ export` download link.

## Export files

Written alongside `index.html`, one set per tab. File naming: `{prefix}{branch}.txt` and `{prefix}main_snapshot.txt` (Java) / `{prefix}main.txt` (Go).

| Tab           | Prefix            | Example files                                                     |
|---------------|-------------------|-------------------------------------------------------------------|
| Java Libs     | `java_libs_`      | `java_libs_lts_25.3.txt`, `java_libs_main_snapshot.txt`           |
| Java Services | `java_services_`  | `java_services_lts_25.3.txt`, `java_services_main_snapshot.txt`   |
| Go Libs       | `go_libs_`        | `go_libs_lts_25.3.txt`, `go_libs_main.txt`                        |
| Go Services   | `go_services_`    | `go_services_lts_25.3.txt`, `go_services_main.txt`                |

Java export format: one `groupId:artifactId:version` line per artifact.  
Go export format: one `module/path@vX.Y.Z` line per module.

## Automation

GitHub Actions workflow (`.github/workflows/lts-versions-report.yml`):

- Triggers: nightly (`0 3 * * *`) and `workflow_dispatch`.
- Checks out the `infra` repository (scripts + config files).
- Clones Java repositories in a shell loop reading both `java-libs-repos.txt` and `java-services-repos.txt` (full clone, needed to read `pom.xml` from disk for monorepo detection).
- The script clones Go repositories internally, reading `go-libs-repos.txt` and `go-services-repos.txt`.
- Installs `jinja2`, runs the script, deploys `_site/` to `gh-pages` under `lts-versions/` (`keep_files: true`).
- `GH_TOKEN` / `MAVEN_RELEASE_DEV_TOKEN` secret is used for all private repository access.
