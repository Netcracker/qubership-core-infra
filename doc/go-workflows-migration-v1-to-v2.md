# Go Workflows Migration: v1.x to v2.x

## What Changed

v1.x: 5 files with check-pr action, lib/service separation, restricted actors logic.
v2.x: 2 files, simplified triggers, unified workflows.

## Files in v2.x

**generic-go-build.yaml** - Reusable workflow in `qubership-core-infra` repository. Call it from your project workflows.

**.github/workflow_templates/go-build.yml** - Template to copy into your project.

## Migration

### Option 1: Copy Template (Recommended)

Copy `.github/workflow_templates/go-build.yml` from qubership-core-infra to your project.

### Option 2: Direct Reusable Workflow Call

**Standard Projects:**

Before (v1.x):
```yaml
jobs:
  build:
    uses: Netcracker/qubership-core-infra/.github/workflows/generic-build-go-lib.yaml@v1.x.x
    with:
      actor: ${{ github.actor }}
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
    secrets:
      sonar-token: ${{ secrets.SONAR_TOKEN }}
```

After (v2.x):
```yaml
jobs:
  build:
    uses: Netcracker/qubership-core-infra/.github/workflows/generic-go-build.yaml@v2.x.x
    with:
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
    secrets: inherit
```

Changes:
- Remove `actor` input
- Replace `generic-build-go-lib` or `generic-build-go-service` with `generic-go-build`
- Use `secrets: inherit`

**Custom Settings (envtest, mono-repo):**

Before (v1.x):
```yaml
jobs:
  build:
    uses: Netcracker/qubership-core-infra/.github/workflows/go-build-with-sonar.yaml@v1.x.x
    with:
      actor: ${{ github.actor }}
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
      install-envtest: true
      go-module-dir: 'cmd/operator'
    secrets:
      sonar-token: ${{ secrets.SONAR_TOKEN }}
```

After (v2.x):
```yaml
jobs:
  build:
    uses: Netcracker/qubership-core-infra/.github/workflows/generic-go-build.yaml@v2.x.x
    with:
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
      install-envtest: true
      go-module-dir: 'cmd/operator'
    secrets: inherit
```

Changes:
- Remove `actor` input
- Keep all other inputs

## Behavior Changes

**Triggers:**
- v1.x: runs on all branches (push + pull_request), double runs for feature branches with PRs
- v2.x: runs on push to main + pull_request only, single run for feature branches with PRs

**Restricted Actors:**
- v1.x: checks actor from build-config.yaml, skips Sonar for restricted actors
- v2.x: no actor checks, Sonar runs if sonar-project-key and SONAR_TOKEN are provided

**Docker Build:**
- v1.x: separate workflows for lib (no docker) vs service (with docker)
- v2.x: docker build always runs, auto-skips for fork PRs or missing docker config

**Manual Triggers:**
- v1.x: workflow_dispatch available
- v2.x: workflow_dispatch removed (not needed for Go projects)

## Required Configuration

Repository variables:
- `SONAR_PROJECT_KEY` (optional)