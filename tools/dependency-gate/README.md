# dependency-gate

Two checks. **Dependency gate** decides whether a dependency change (a Renovate PR or a manual bump) stays inside
the pins and does not expose a new Go API break. **Consumers** decides whether that change breaks a reference
consumer. Automerge waits for both; neither verdict waits for the other.

```
adapters (find)                          gate (decide)
  maven-pins     ─┐                        levels, locked
  go-library     ─┴─ findings.v1       ─▶  ignore rules, baseline,   ─▶ Dependency gate
  maven-consumers ─┐  (head)               mode                         report, summary, comment
  go-consumers   ─┘  (head, base)      ─▶  the same policy          ─▶ Consumers
                        infra defaults.json + .github/dependency-gate.json (config.v1)
```

Adapters only find problems and know nothing about levels or baselines. The gate is the only place
where the policy is applied, locally and in CI alike.

| File | What it is |
|---|---|
| [`schemas/config.v1.json`](schemas/config.v1.json) | configuration: infra defaults and the repository file |
| [`schemas/findings.v1.json`](schemas/findings.v1.json) | what an adapter writes |
| [`schemas/report.v1.json`](schemas/report.v1.json) | what the gate writes |
| [`defaults.json`](defaults.json) | infra defaults: mode, protected branches, locked checks, pins |

Schemas and defaults are built into the binary, so a revision of qubership-core-infra fixes the gate, the defaults
and the contracts together.

## Running locally

```bash
go run github.com/Netcracker/qubership-core-infra/tools/dependency-gate@<sha> evaluate -findings target/dependency-gate
```

`evaluate` reads `.github/dependency-gate.json` of the current directory (`-repo` for another one) and every
`*.json` under `-findings`, prints the job summary and exits with

| Code | Meaning |
|---|---|
| 0 | pass or warn, or any verdict in `report` mode |
| 1 | fail in `enforce` mode |
| 2 | the gate itself failed (bad flags, broken infra defaults) |

Other flags: `-out <dir>` writes `report.json`, `summary.md` and `comment.md`; `-branch` is the target branch
(feature SNAPSHOT versions are allowed outside protected branches); `-label <name>` simulates PR labels (see
`labels.levels`); `-today yyyy-mm-dd` is accepted and unused; `-defaults <file>` replaces
the built-in infra defaults; `-config-rev <rev>` reads the repository file from a git revision (on pull requests
in GitHub Actions it is `HEAD^1`, the base branch). Without findings, `evaluate` validates the repository
configuration.

`detect` prints what the CI would check: Maven roots and Go modules, whether their manifests changed since `-base`,
and the effective configuration.

`compare <a> <b>` prints `-1`, `0` or `1` as Go version `a` is below, equal to or above `b`: module versions as
semver (pre-releases and pseudo-versions included), go directives (`1.21rc1`) as Go versions.

## Configuration

The repository file `.github/dependency-gate.json` is optional; without it the infra defaults apply.

```json
{
  "$schema": "https://raw.githubusercontent.com/Netcracker/qubership-core-infra/<ref>/tools/dependency-gate/schemas/config.v1.json",
  "version": 1,
  "ecosystems": {
    "maven": { "excludes": ["^core-maven-plugins/.*/src/test/resources/"], "transitive": "warn" },
    "go": { "modules": ["."] }
  },
  "checks": {
    "apidiff-exposed": {
      "ignore": [{ "package": "/^github\\.com/openshift/api//", "reason": "Breaks in every release; coordinated with consumers" }]
    }
  }
}
```

### Merge

Built-in check catalog → infra `defaults.json` → repository file:

- objects (`checks`, `ecosystems`, `labels.levels`) merge by key, the repository wins;
- `families` merge by `name`; `checks.<id>.ignore` adds up, each entry keeps its source
  (`.github/dependency-gate.json#/checks/apidiff-exposed/ignore/0`);
- `protectedBranches`, `locked`, `consumers` and `ecosystems.maven.pins`
  belong to infra; a repository may only disable consumers with `"consumers": false`. A consumer with
  `"tests": true` also has its tests run (Go).
- `ecosystems.maven.excludes` are Java regexes (`java.util.regex`) of pom paths, one pattern per entry without line
  breaks; the Maven plugin applies and validates them. `ecosystems.go.exclude` are RE2 regexes.
- `ecosystems.maven.roots` and `ecosystems.go.modules` are repository-relative directories without commas or `..`;
  when set, nothing else of the ecosystem is checked.
- The roots and modules a repository sets, what it excludes and its `transitive` level narrow every check of the
  ecosystem, locked ones included: an excluded pom is not checked at all, `"transitive": "off"` drops what only the
  dependency tree shows. This is the repository's decision, like `mode`; the report (`scope`) and the job summary
  show it on every run.

On pull requests the repository file is read from the base branch (`HEAD^1` of the merge commit), not from the
change: a pull request cannot relax its own gate, and an edit of the file applies from the next pull request on. Pushes
and manual runs read the checked-out file.

Any problem in the repository file is `config-invalid`: the whole file is ignored until
fixed and the gate falls back to the infra defaults, so a broken file never weakens a check.
An `exceptions` array is such a problem, in the repository file and in the infra defaults.

### Levels

`fail` turns the check red (in `enforce` mode), `warn` reports with a green check, `info` only reports, `off` drops the findings.
The level of a finding is, in order: the catalog default, `checks.<id>`, `ecosystems.<eco>.checks.<id>`, then
`labels.levels.<label>.<id>` for the labels of the PR (the highest wins). Labels are read from the event of the run
and a label change does not start one: it applies from the next push.

- **locked** (infra): a repository cannot set a lower level, add ignore rules, or use label levels for these checks.
  A new pin exceedance stays a fail. The same key already on the base branch is preexisting and does not block.
  Which poms and which findings reach them is still up to the repository: see `excludes` and `transitive` above.
- Checks of the gate (`config-invalid`, `tool-error`) take no ignore rules or label
  levels, and a repository may only raise their level above the catalog default (`tool-error: fail` also makes a
  `go-consumers` or `maven-consumers` failure block). A repository file cannot make a failed adapter pass.
- `feature-snapshot` applies only when the target branch matches `protectedBranches`.
- `ecosystems.<eco>.transitive` caps findings that only the resolved dependency tree shows (`"transitive": true`):
  infra sets `warn` for Maven, `fail` makes them count like declared ones, `off` drops them.

### Ignore rules

An ignore rule selects findings with `package`, `version`, `symbol`, `vuln`, `license` and `path` (a location file). Each value is
exact, `*`, or `/RE2 regex/` with an optional `i` flag; `version` needs `package` and defaults to `*`. At least one
selector is required. `checks.<id>.ignore[]` is a permanent setting of a check and needs `reason`. A matched finding
gets the status `allowed` and does not count. Locked checks take no ignore rules.

`exceptions` is not policy. A file that contains the array is `config-invalid` and is ignored. A finding already on
the base branch is preexisting; a new one keeps the check's own level.

### Baseline

On pull requests adapters also report the base revision (`"revision": "base"`). A head finding whose key is also on
the base gets the status `preexisting` and at most `warn`: it does not block a change that did not introduce it.
The key is `ecosystem|check|package|version|symbol`, plus `|transitive` for transitive findings, without
locations, so moving code does not make a finding new.
The version is part of the key: a violation that gets worse (log4j 2.26.1 → 2.26.2 above the pin) is new.

### Mode and merging

- `mode: report` (the pilot, infra default) computes the verdict but the check stays green; the summary says
  "would fail". `mode: enforce` fails the check on a `fail` verdict. A repository can set either mode: switch to
  `enforce` early or stay on `report`.
- The gate is not a required check: a person with merge rights can merge a pull request with a red gate after
  checking what it found, as with a red link checker or Sonar. A red gate stops only the Renovate automerge: the
  automerge rules in `.github/config/renovate.yml` set `platformAutomerge: false`, so Renovate itself waits for every
  check of the branch (the native auto-merge of GitHub waits only for required ones).
- In `mode: report` the check stays green, so the gate does not stop the automerge. To stop it, a repository sets
  `mode: enforce` in its `.github/dependency-gate.json`.
- For Maven the pin check compares versions and builds nothing. Automerge waits for the repository's own Build
  check, for Dependency gate and for Consumers.

## Contracts

### findings.v1 (adapters)

```json
{
  "schema": "dependency-gate-findings/v1",
  "adapter": { "name": "maven-pins", "ecosystem": "maven", "tool": "dependency-gate-maven-plugin 0.1.0-SNAPSHOT", "status": "ok",
               "checks": ["pin-exceeded", "pin-mismatch", "pin-lagging"] },
  "revision": "head",
  "findings": [
    { "check": "pin-exceeded", "package": "com.fasterxml.jackson:jackson-bom", "version": "2.21.7",
      "message": "2.21.7 is above the pin 2.21.5 (allowedVersions /^2.21.5$/)",
      "locations": [{ "file": "pom.xml", "line": 87 }] }
  ]
}
```

- `ecosystem`: on the adapter, or on each finding when one adapter covers several ecosystems (OSV); one of them is
  required. Baselines and levels work per finding ecosystem.
- `transitive: true`: the finding comes only from the resolved dependency tree, not from what the repository declares
  or manages.
- `adapter.status`: `ok`; `error` with `reason` when the adapter could not finish (→ `tool-error`); `skipped`.
- `adapter.checks`: the checks that actually ran.
- `revision`: `head` (the PR merge commit) or `base` (its first parent), one file per adapter and revision.
- `check` must be a catalog id of the adapter's ecosystem; `package`, `version`, `from` (the version before the
  change), `symbol`, `vuln`, `license`, `note` may be null. `locations[].file` is repository-relative with `/`, `unit`
  names the module; annotations need a `line`.

A file that does not match the schema, a finding without an ecosystem or with a check unknown for it, or an adapter that was expected (`-expect name=<job result>`)
but wrote nothing is a `tool-error` of that adapter: the gate fails closed (`fail`; `warn` for `go-consumers` and `maven-consumers`).

### report.v1 (the gate, artifact `dependency-gate-report`)

`scope` holds what the configuration keeps from the adapters by ecosystem: the configured `roots` (Maven) or
`modules` (Go), `exclude` patterns and the `transitive` level. Only what is set is listed.
`findings[]` carry the adapter fields (`ecosystem` is absent for gate findings) plus `key`, `adapter`, the effective
`level` and a `status`: `new`, `preexisting` or `allowed` (with `exception`: `source`, `reason`, `until`, `issue`).
`counts.fail/warn/info` count new findings only. `verdict` is `fail` with any new `fail`, `warn` with
any non-allowed `warn`, else `pass`; `mode` tells whether it was enforced.
Before writing, the gate validates its own report against the schema.

## Checks

| Id | Ecosystem | Default | Catches |
|---|---|---|---|
| `pin-exceeded`, `pin-mismatch` | maven | fail, locked | a version past the Renovate pin / not matching a pin: a pattern (`/^2\.28\./`, `!/^3\.6\.0$/`), or an exact pin written differently (`5.30` for `/^5.30.0$/`) |
| `pin-lagging`, `shared-property` | maven | warn | below the pin; one property versions artifacts with different pins |
| `pin-conflict` | maven | warn | a BOM allowed by its own rule manages versions above their pins |
| `feature-snapshot` | maven | fail, locked, protected branches only | `X.Y.Z-<branch>-SNAPSHOT` versions |
| `snapshot` | maven | warn | any SNAPSHOT not built by the repository |
| `apidiff-exposed` | go | fail | incompatible dependency API visible in our API |
| `family-split` | maven / go | off / fail | Go modules released together end up on different versions |
| `consumer-build` | maven / go | fail | a reference consumer no longer builds with the change |
| `consumer-test` / `consumer-test-flaky` | maven / go | fail / info (flaky is Go) | a test of a reference consumer fails with the change / failed and passed on a rerun |
| `config-invalid` | gate | fail | the repository configuration is invalid |
| `tool-error` | gate | fail; warn for `go-consumers` and `maven-consumers` | an adapter failed or broke the contract |

## Enabling the checks

Add `.github/workflows/dependency-gate.yml` to the repository that should be checked:

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

Do not add a `paths` filter. `detect` already skips an ecosystem whose manifests did not change, and a workflow
that never starts gives Renovate no check to wait for.

This call, with no `with`, no secret and no `.github/dependency-gate.json`, runs two GitHub checks. `mode` is
`report`: a fail verdict leaves the check green and the summary says "would fail".

**Dependency gate** — versions against the pins, and Go API breaks.

| When | Checks |
|---|---|
| `pull_request`, and `pom.xml` or `.mvn/**` changed | `pin-exceeded`, `pin-mismatch` (fail, locked); `pin-lagging`, `pin-conflict`, `shared-property`, `snapshot` (warn); `feature-snapshot` (fail, locked, only into `main`, `master`, `release/*`, `support/*`). Head and its base. A version only the dependency tree shows is at most warn. |
| `pull_request`, and `go.mod` or `go.sum` changed | `family-split` (fail, head and its base), `apidiff-exposed` (fail, head). |
| `push`, `workflow_dispatch` | the same checks for every ecosystem the repository has. There is no base, so every finding is new. |

**Consumers** — `pull_request` only. `push` and `workflow_dispatch` do not run it.

| When | Checks |
|---|---|
| the repository has Maven | `consumer-build`, `consumer-test` of `Netcracker/qubership-core-config-server` and `Netcracker/qubership-core-core-operator` (`mvn verify -DskipITs`). A consumer that does not reference this build is skipped. |
| the repository has Go, and a reference consumer depends on it | `consumer-build`. A consumer the infra list marks with tests also runs `consumer-test` (`consumer-test-flaky` is info). |

A Go consumer finding with the same key on the base build is preexisting and does not block. A Maven consumer
has no base build: a failed `mvn verify` against the pull request is new.

### Repository nothing consumes

A service is not a library the reference consumers compile against. Use the same caller and add
`.github/dependency-gate.json` on the default branch. A pull request reads this file from its base, so it applies
from the next pull request.

```json
{
  "version": 1,
  "mode": "enforce",
  "consumers": false
}
```

**Dependency gate** runs as in the table above. Renovate security updates (`vulnerabilityAlerts`,
`vulnerabilityFixStrategy: lowest`) ignore `allowedVersions` and will automerge a minor or patch above the pin
once every check is green. `pin-exceeded` is fail and locked. `mode: enforce` makes that fail the check, so
Renovate waits.

**Consumers** does not run, and neither does the job that installs this repository as a library. That install
rewrites every `X.Y.Z-SNAPSHOT` in the checkout, including an imported BOM, and then fails. With
`"consumers": false` the job plans an empty list and skips the install.

### Workflow inputs

These do not choose which checks run.

| Input | Default | Effect |
|---|---|---|
| `comment` | `true` | sticky comment of **Dependency gate** (`<!-- dependency-gate -->`): created when the change brings a new warn or fail, updated afterwards, never on a fork. `false` leaves the job summary and the annotations. **Consumers** does not comment. |
| `java-version` | `"21"` | JDK for the Maven pin check and the Maven consumer builds |
| `maven-username` | the actor | GitHub Packages user of `MAVEN_TOKEN` |
| secret `MAVEN_TOKEN` | the workflow token | read-only token (`read:packages`) when the workflow token cannot read a package. Do not pass a token that can write or delete packages: the Maven job runs build extensions from the pull request. A fork pull request gets no secrets; Maven still reads packages with the read-only workflow token, and the comment is skipped. |

```yaml
jobs:
  dependency-gate:
    uses: Netcracker/qubership-core-infra/.github/workflows/dependency-gate.yml@<sha>
    with:
      comment: false
      java-version: "21"
    secrets:
      MAVEN_TOKEN: ${{ secrets.MAVEN_TOKEN }}
```

### Repository file

`.github/dependency-gate.json` is optional. On a pull request it is read from the base branch (`HEAD^1`), so an
edit applies from the next pull request. A file the schema rejects is `config-invalid`: the file is ignored and
the infra defaults apply. An `exceptions` array is rejected.

| Field | Default | What it changes |
|---|---|---|
| `mode` | `report` | `enforce`: a new fail fails the check |
| `consumers` | the infra list | `false`: **Consumers** does not run. **Dependency gate** still does. A repository cannot replace the list. |
| `ecosystems.maven.transitive` | `warn` | `off` drops findings only the dependency tree shows; `fail` counts them like declared versions. Locked checks included. |
| `ecosystems.maven.excludes` | none | Java regexes of pom paths. A matching pom is not checked, locked checks included. |
| `ecosystems.maven.roots` | the reactor root, or top-level poms that no other pom lists as a module | only these directories. Nothing else of Maven is checked. |
| `ecosystems.go.modules` | every `go.mod` outside `vendor/` and `testdata/` | only these directories |
| `ecosystems.go.exclude` | none | RE2 regexes of module directories not to check |
| `checks.<id>` | the catalog level | `fail`, `warn`, `info` or `off`. Locked checks (`pin-exceeded`, `pin-mismatch`, `feature-snapshot`) cannot be lowered, and they take no ignore rules. |
| `checks.<id>.ignore` | none | a matched finding is allowed and does not count. Needs `reason`. |

A repository cannot set the pins file, `protectedBranches` or `locked`.

`permissions` in the caller are the ceiling. The workflow narrows them per job: `contents: read` everywhere,
`packages: read` for Maven, `pull-requests: write` only for the comment. The Maven plugin is installed from
`tools/dependency-gate-maven-plugin` of the called revision and is not published. The `maven` job passes every
`dependency-gate.*` parameter with `-D`, so a `-D` in the pull request's `.mvn/maven.config` cannot replace one.

## Development

```bash
go test ./...
```

Tests run the real schemas and `defaults.json` on the fixtures in `internal/gate/testdata`.
