# Go dependency check

Go adapters of the dependency gate (jobs `go-library` and `go-consumers` of
[`.github/workflows/dependency-gate.yml`](../../workflows/dependency-gate.yml)). They only find problems and write
findings.v1 files; levels, ignore rules, the baseline and the report belong to the gate
([`tools/dependency-gate`](../../../tools/dependency-gate/README.md)). Module families and reference consumers come
from the gate configuration (`families` with `ecosystem: go`, `consumers.go` in the infra defaults). The gate is not a required check: in `mode: enforce` it stops
the Renovate automerge, and a person with merge rights can merge with a red gate.

## go-library (`go-dep-check.sh <repo> <base> <head> <out> [<module>...]`)

In CI `head` is the PR merge commit and `base` its first parent, so a stale PR is checked as it would be merged.
A module whose go.mod/go.sum did not change is skipped. Without a base (push, manual run) only the checks of one
revision run.

| Check | What it finds | Revisions |
|---|---|---|
| `family-split` | modules of a family (bun, otel v1, k8s.io staging, testcontainers) on different versions, indirect ones included | both |
| `apidiff-exposed` | apidiff of a dependency package the module imports (tests included) when that package's types appear in the module's exported API (`go doc -all`): one finding per changed top-level symbol our code references as `pkg.Symbol`, one (`pkg.*`) for the rest; a package apidiff cannot compare is a finding too (the change is unknown). A break that stays inside the module is not reported | head |

The gate marks a head finding whose key is also on base as already on the base branch (`preexisting`), so a split
or an exposed break that was already on the base branch does not block the change.

## go-consumers (`consumer-check.sh <consumer> <lib-base> <lib-head> <out> <name> [<tests>]`)

Reference consumers (`consumers.go` in the infra defaults) are the Go services and every `qubership-core-lib-go*`
library. The `plan` mode of the action keeps those whose go.sum mentions the library (a library is never in its own
go.sum); the consumer job confirms with `go list -deps -test` (a failing `go list` is an error, not a skip) and
builds the consumer with `replace <lib> => <lib at base>` and `=> <lib at head>` (+ `go mod tidy`, so MVS raises
the library's own dependencies like a real bump). `consumer-build` findings, with the aspect as `symbol`: `build`
and `tests` (compile, test-binary init) on both revisions, `vet` (new findings) on head. The matrix runs at most 10
consumers at a time.

A consumer with `"tests": true` (the libraries: their tests need only Docker for testcontainers, no secrets) also runs
its tests on head when they build: `gotestsum --rerun-fails=2 -- -count=1 -timeout 15m ./...` (pinned in
`action.yml`), so a failed test runs up to twice more. One finding per failed top-level test (a failed subtest fails
its parent), `symbol` = `<import path>.<Test>`, or per package that failed without a failed test (`symbol` = the
import path):

- `consumer-test` — it failed in the last run too;
- `consumer-test-flaky` — a rerun passed. Level `info`: base never runs a test that passed on head, so a flake the
  change brings looks the same as an old one; the report counts them without a PR comment.

Only the tests that still fail on head run again on base (a package that failed as a whole runs whole); a key that
fails there too is preexisting. A green change runs the tests once. gotestsum does not rerun anything after a panic
or when more than 10 tests failed: those all count as failed.

### Time

Measured locally (WSL, 16 cores, warm module cache, no Docker, so testcontainers suites failed at once):
build and vet of base and head take 8–33 s per library consumer; with tests 8–76 s. A green lib-go branch
(`renovate-main/major-go-major`) and dbaas-base-client `main` gave no new findings.

In CI a consumer job adds checkout, setup-go, a cold module cache and container image pulls: about 3–6 min, up to
~10 min for maas-bg-segmentio (Kafka). A lib-go change has 28 consumers (10 services, 18 libraries): three waves of
10, about 15–25 min until the gate and 100–170 runner minutes. dbaas-base-client has 9 consumers in one wave, about
6–10 min. Failing tests add their reruns on head and one run on base.

## Run locally

```bash
go run github.com/Netcracker/qubership-core-infra/tools/dependency-gate@<sha> detect -repo <lib> | jq -r .config > /tmp/gate-config.json
DEP_GATE_CONFIG=/tmp/gate-config.json .github/actions/go-dep-check/go-dep-check.sh <lib> origin/main origin/renovate-main/go-minor /tmp/findings
.github/actions/go-dep-check/consumer-check.sh <service>/<module> <lib-at-main> <lib-at-branch> /tmp/findings <repo>/<module> [true]
go run github.com/Netcracker/qubership-core-infra/tools/dependency-gate@<sha> evaluate -repo <lib> -findings /tmp/findings
```

Needs `go`, `jq`, and `apidiff` (library checks) and `gotestsum` (consumer tests) in `$(go env GOPATH)/bin` (versions: see
`action.yml`).
