# Common Linter

This branch builds the mandatory lint image on [GitHub Super-Linter](https://github.com/super-linter/super-linter). It ships the required lint rules and the `lint --path` command.

## Build

```bash
make build
```

## Push

```bash
make push
```

## CI build

The image is built and published by the repository's `CI: Dev Docker Build` workflow, configured in `.qubership/docker.cfg`. The workflow builds the `linter` component from `linter/Dockerfile` and pushes it to the GitHub Container Registry (`ghcr.io`).

## Mandatory lint checks

Run `lint --path .` inside the linter image. The image enables the mandatory
checks by default. See [mandatory lint usage](docs/usage.md#mandatory-lint-checks)
for the project discovery rules and a GitLab job.

## Run locally

Mount the Git checkout at `/tmp/lint` and run the image:

```bash
docker run --rm -v "$PWD:/tmp/lint" ghcr.io/netcracker/linter:git-submodules-test
```

## Existing common-linter tags

The following Makefile commands use common-linter tags that include the
original wrapper and skillcheck.

From a consumer repository that contains the consumer `Makefile`:

```bash
make lint                                   # Run Super-Linter + skill validation (when SKILLCHECK_PATTERNS is set)
make lint-super-linter                      # Run Super-Linter only
make skill-validate                         # Run skillcheck only (skips when SKILLCHECK_PATTERNS is empty)
make lint FILTER_REGEX_INCLUDE=<regex>      # Run lint on a filtered set of files
make lint-fix                               # Run Super-Linter with auto-fix enabled
make verify                                 # Run lint and tests
make verify FILTER_REGEX_INCLUDE=<regex>    # Run lint on a filtered set of files; tests run for the changed language
make test                                   # Run Go, Python and Java tests (locally on the host; in CI inside the job/container image)
make test-go                                # Auto-detect Go modules and run go test ./... where tests exist
make test-python                            # Run Python tests (skipped if PY_BUILD is not set or no .py files changed)
make test-java                              # Auto-detect Maven modules and run mvn test when Java/XML files changed
```

For full usage instructions, including CI and GitHub Actions examples, see [`docs/usage.md`](docs/usage.md).

For which Super-Linter checks are enabled or disabled, plus `test-go` / `test-python` / `test-java` and skillcheck, see [`docs/supported-checks.md`](docs/supported-checks.md).

See `templates/common.mk` for the consumer template and `super-linter.env` for baseline environment variables.
