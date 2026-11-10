# Dependency gate

## What it does

Two GitHub checks. **Dependency gate** decides whether a Go dependency change exposes a new API break. A Maven version bump is not a finding here: the gate does not compare Maven versions with pins. **Consumers** decides whether that change breaks a reference consumer. Automerge waits for both because `platformAutomerge` is false; neither verdict waits for the other.

Adapters write `findings.v1` and know nothing about levels or baselines. The gate applies the policy: the built-in catalog, `defaults.json`, then the optional repository file.

A new fail fails the check. The checks are not required. A red gate stops Renovate automerge and does not stop a person with merge rights. `dry-run` does not change the verdict. The summary and the comment add a warning and show the same findings, and the check stays green.

## Files

```
dependency-gate/
├── ci.py                # workflow steps that are not a single command
├── consumers.py         # Go and Maven reference consumers (plan, check)
├── defaults.json        # families, reference consumers
├── schemas/             # config.v1, findings.v1, report.v1
├── main.go              # detect, evaluate, compare
└── DESIGN.md
```

Repository names and module directories are `.github/repos.json`. `defaults.json` adds `tests` and the test-app modules. `maven.libs` is the Java monorepo for the LTS report; the gate does not build it.

Schemas and defaults are built into the binary. A revision of qubership-core-infra fixes the gate, the defaults and the contracts together.

## CLI

```
dependency-gate detect   -repo <dir> -consumer-lists <repos.json>
dependency-gate evaluate -repo <dir> -findings <dir> -out <dir> -consumer-lists <repos.json>
dependency-gate compare <a> <b>
```

| Exit | Meaning |
|---|---|
| 0 | pass, warn, or dry-run |
| 1 | a new fail |
| 2 | the gate itself failed (bad flags, broken infra defaults, a report that does not match the schema) |

`detect` prints whether Maven and Go have something to check, the roots and modules, and the effective configuration. `-base` is the revision to compare with HEAD (default `HEAD^1` on a pull request; empty checks every manifest). `-consumers true|false` overrides reference consumers.

`evaluate` reads `.github/dependency-gate.json` when the file exists (`-config-rev` for a git revision; pull requests use `HEAD^1`). Without findings it still validates that configuration. `-expect name=<job result>` names an adapter that must have reported; `skipped: reason` records why a skipped job ran nothing. `-label` adds a pull request label (`labels.levels`). `-defaults` replaces the built-in defaults. `-comment` writes the Dependency gate comment (`<!-- dependency-gate -->`). `-comment-note` writes the Consumers comment (`<!-- dependency-gate-consumers -->`). `-dry-run` skips the failure. `-gate` is the version written to the report.

`compare` prints `-1`, `0` or `1`. Module versions are semver, pre-releases and pseudo-versions included (`v1.2.0-rc.1` < `v1.2.0`). A value without the `v` prefix is a Go version (`1.21rc1` < `1.21.0` < `1.22`). An invalid version is the lowest.

The verdict is fail when a new finding is fail, warn when a finding is warn and none is a new fail, otherwise pass. `counts` of fail, warn and info count new findings only. An allowed finding does not count. `off` drops the finding. `info` does not change the verdict. `warn` leaves the check green.

In GitHub Actions the job summary and the annotations are written on every run. A new fail is an error annotation; any other warn with a line is a warning. `-comment false` leaves both and does not post the sticky comment.

## Configuration

The common case has no `.github/dependency-gate.json`. Callers change the defaults they actually change with workflow `with` inputs. Defaults apply when an input is omitted: reference consumers on, dry-run off.

The caller workflow's `with` is the override. GitHub reads that workflow file from the branch that runs, not from the base of the pull request. A pull request that edits the caller workflow changes `consumers` and `dry-run` for that run. Previously the repository file was the override and a pull request read it from the base. That base-branch read is not how these overrides work.

The repository file, when a repository has one, is still read from the base on a pull request (`HEAD^1` of the merge commit). A pull request cannot add an ignore rule or lower a check in that file. A file the schema rejects is `config-invalid`: the file is ignored, the infra defaults apply, and the check fails. A `mode` field is not configuration and is rejected the same way. An `exceptions` array is rejected the same way.

Ignore rules stay in that file. Roots, modules, exclude, check levels, families and label levels stay there too: they are not a few defaults. The file is not required.

Merge: objects merge by key and the repository wins; `families` merge by `name`; ignore rules add up, and each rule keeps its source. `locked` and the consumer list belong to infra. The workflow input `consumers` replaces a `consumers` value in the file, including `false`. Roots and modules are repository-relative directories without commas or `..`; when set, nothing else of that ecosystem is checked. The report's `scope` and the job summary show that on every run.

| Field | What it changes |
|---|---|
| `ecosystems.maven.roots` | only these directories count as this repository having Maven |
| `ecosystems.go.modules` | only these directories |
| `ecosystems.go.exclude` | RE2 regexes of module directories to skip |
| `checks.<id>` | `fail`, `warn`, `info` or `off`. A locked check cannot be lowered, and it takes no ignore rules and no label levels. The defaults lock nothing. |
| `checks.<id>.ignore` | a matched finding is allowed and does not count. Needs `reason`. A value is exact, `*`, or `/RE2/` with an optional `i` flag, on `package`, `version`, `symbol`, `vuln`, `license` or `path`. `version` needs `package` and defaults to `*`. |

A finding level is the catalog default, then `checks.<id>`, then `ecosystems.<eco>.checks.<id>`, then the highest `labels.levels` of the pull request. Labels are read from the event; a label change applies from the next push. `config-invalid` and `tool-error` take no ignore rules and no label levels. A repository may only raise them. Raising `tool-error` makes a `go-consumers` or `maven-consumers` failure fail the check. A repository cannot make a failed adapter pass.

On a pull request a head finding whose key is also on the base is `preexisting` and at most `warn`. The key is `ecosystem|check|package|version|symbol`, without locations, so moving code does not make a finding new. A different version of the same break is new.

## Checks

| Id | Default | Catches |
|---|---|---|
| `apidiff-exposed` | go fail | an incompatible dependency API visible in the module's exported API |
| `family-split` | maven off, go fail | modules of one family on different versions |
| `consumer-build` | fail | a reference consumer no longer builds |
| `consumer-test` | fail | a test of a reference consumer fails |
| `consumer-test-flaky` | go info | a Go test failed and passed on a rerun |
| `config-invalid` | fail | the repository file is invalid |
| `tool-error` | fail; warn for `go-consumers` and `maven-consumers` | an adapter failed or broke the contract |

The contracts are `schemas/findings.v1.json` and `schemas/report.v1.json`. A file that does not match, or an expected adapter that wrote nothing, is a `tool-error` of that adapter. A skipped job is not an error.

## Enabling the checks

Caller repository, `.github/workflows/dependency-gate.yml`. No `paths` filter: `detect` skips an ecosystem whose manifests did not change, and a workflow that never starts gives Renovate no check to wait for.

```yaml
name: Dependency gate

on:
  pull_request:
    types: [opened, synchronize, reopened]
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  packages: read
  pull-requests: write

jobs:
  dependency-gate:
    uses: Netcracker/qubership-core-infra/.github/workflows/dependency-gate.yml@<sha> # vX.Y.Z
```

This call, with no `with`, runs both checks. Reference consumers are on. A new fail fails the check.

**Dependency gate.** Go API breaks. A Maven-only change produces no findings here.

| When | What runs |
|---|---|
| `pull_request`, and `go.mod` or `go.sum` changed | `family-split` and `apidiff-exposed`, head and its base |
| `push`, `workflow_dispatch`, repository has Go | the same checks. There is no base, so every finding is new. |

**Consumers.** `pull_request` only.

| When | What runs |
|---|---|
| the repository has Maven | `mvn verify -DskipITs` of `qubership-core-config-server` and `qubership-core-core-operator`. A consumer that does not reference this build is skipped. There is no base build: a failure is new. |
| the repository has Go, its non-test build list changed, and a reference consumer depends on it | build and tests of the Go libraries, Go services and the test-app modules. A dependency only tests import does not start the matrix. The same key on the base build is preexisting. |

An empty Go plan still publishes **Consumers**, with the reason: the non-test build list is unchanged, or no reference consumer depends on the module.

A service that nothing consumes uses the same caller:

```yaml
jobs:
  dependency-gate:
    uses: Netcracker/qubership-core-infra/.github/workflows/dependency-gate.yml@<sha> # vX.Y.Z
    with:
      consumers: false
```

**Dependency gate** still runs. **Consumers** does not. The Maven library job plans an empty list and skips the install.

To see a fail without failing the check:

```yaml
    with:
      dry-run: true
```

| Input | Default | Effect |
|---|---|---|
| `comment` | `true` | two sticky comments, never on a fork. **Dependency gate** (`<!-- dependency-gate -->`) is created when the change brings a new warn or fail and updated afterwards. **Consumers** (`<!-- dependency-gate-consumers -->`) is its own comment: the fail fact and a link to the run. A later run whose verdict is not fail deletes it. `false` leaves the job summary and the annotations. |
| `consumers` | `true` | `false` turns the Consumers check off. The list itself stays in `.github/repos.json`. |
| `dry-run` | `false` | a new fail does not fail the check. The summary and the comment add `Warning: dry run. The check stays green.` and show the same findings. |
| `java-version` | `"21"` | JDK for the Maven library build and the Maven consumer builds |
| `maven-username` | the actor | GitHub Packages user of `MAVEN_TOKEN` |
| secret `MAVEN_TOKEN` | the workflow token | read-only token (`read:packages`) when the workflow token cannot read a package. Never a token that can write or delete packages. A fork gets no secrets; the comment is skipped. |

```yaml
jobs:
  dependency-gate:
    uses: Netcracker/qubership-core-infra/.github/workflows/dependency-gate.yml@<sha> # vX.Y.Z
    with:
      comment: false
      java-version: "21"
    secrets:
      MAVEN_TOKEN: ${{ secrets.MAVEN_TOKEN }}
```

## Workflow

`.github/workflows/dependency-gate.yml` checks the caller out and calls actions. `dependency-gate/consumers.py` plans and checks reference consumers; `go` and `maven` are its parameter. `dependency-gate/ci.py` holds the other steps that decide something: the snapshot suffix and the findings file a crashed matrix job still has to leave. `git worktree`, `go build` and `go install` stay one command each, because that command is the step. The Go library adapter (family-split, apidiff) stays beside its action.

`permissions` in the caller are the ceiling. The workflow narrows them per job: `contents: read` everywhere, `packages: read` for Maven, `pull-requests: write` only for the comment.
