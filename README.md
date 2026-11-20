# Mandatory linter

This branch builds the mandatory lint image. It runs the required Super-Linter
checks for Go modules, Python, Java, Bash, Dockerfiles, YAML, and Markdown. It
also runs strict Helm linting for charts in scope.

Build and run it from a Git checkout:

```sh
docker build -t mandatory-linter ./linter
docker run --rm -v "$PWD:/tmp/lint" mandatory-linter --path /tmp/lint
```

Set `--path` to a directory within the mounted checkout to narrow the scan.
For a chart with dependencies, make them available before linting. Set an
optional flag such as `VALIDATE_JAVASCRIPT_ES=true` to enable an extra check.

The GitHub workflow publishes the feature-branch tag as
`ghcr.io/netcracker/linter:git-submodules-test`. The GitLab lint job pulls it
through `artifactorycn.netcracker.com:17114`.
