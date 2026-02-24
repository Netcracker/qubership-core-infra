# Maven Workflows Migration: v1.x to v2.x

## What Changed

v1.x: 4 files with check-pr action, lib/service separation, restricted actors logic, complex goal selection.
v2.x: 3 files, simplified triggers, explicit maven commands, unified workflows.

## Files in v2.x

**generic-maven-build.yaml** - Reusable workflow in `qubership-core-infra` repository. Call it from your project workflows.

**.github/workflow_templates/maven-deploy.yml** - Template for deployment (push to main + manual dispatch).

**.github/workflow_templates/maven-verify.yml** - Template for PR validation.

## Migration

### Option 1: Copy Templates (Recommended)

Copy both templates from qubership-core-infra to your project:
- `.github/workflow_templates/maven-deploy.yml`
- `.github/workflow_templates/maven-verify.yml`

### Option 2: Direct Reusable Workflow Call

**Standard Projects:**

Before (v1.x):
```yaml
jobs:
  build:
    uses: Netcracker/qubership-core-infra/.github/workflows/generic-build-maven-lib.yaml@v1.x.x
    with:
      actor: ${{ github.actor }}
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
    secrets:
      sonar-token: ${{ secrets.SONAR_TOKEN }}
```

After (v2.x) - for deployment:
```yaml
jobs:
  deploy:
    uses: Netcracker/qubership-core-infra/.github/workflows/generic-maven-build.yaml@v2.x.x
    with:
      maven-command: 'deploy'
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
      ref: ${{ github.ref }}
    secrets: inherit
```

After (v2.x) - for PR validation:
```yaml
jobs:
  verify:
    uses: Netcracker/qubership-core-infra/.github/workflows/generic-maven-build.yaml@v2.x.x
    with:
      maven-command: 'verify'
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
    secrets: inherit
```

Changes:
- Remove `actor` input
- Add explicit `maven-command` ('deploy' or 'verify')
- Replace `generic-build-maven-lib` or `generic-build-maven-service` with `generic-maven-build`
- Use `secrets: inherit`

**Custom Settings (custom pom, Java version):**

Before (v1.x):
```yaml
jobs:
  build:
    uses: Netcracker/qubership-core-infra/.github/workflows/maven-build-with-sonar.yaml@v1.x.x
    with:
      event-name: ${{ github.event_name }}
      actor: ${{ github.actor }}
      java-version: '17'
      pom-file-path: 'submodule/pom.xml'
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
    secrets:
      sonar-token: ${{ secrets.SONAR_TOKEN }}
```

After (v2.x):
```yaml
jobs:
  build:
    uses: Netcracker/qubership-core-infra/.github/workflows/generic-maven-build.yaml@v2.x.x
    with:
      maven-command: 'deploy'
      java-version: '17'
      pom-file: 'submodule/pom.xml'
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
      ref: ${{ github.ref }}
    secrets: inherit
```

Changes:
- Remove `event-name` and `actor` inputs
- Add explicit `maven-command`
- Rename `pom-file-path` to `pom-file`
- Keep `java-version` as is

## Behavior Changes

**Triggers:**
- v1.x: runs on all branches (push + pull_request), double runs for feature branches with PRs
- v2.x: separate workflows for deploy (push to main) and verify (PR), single run per event

**Maven Command Selection:**
- v1.x: automatic based on event-name, actor, and dry-run (complex logic in prepare-variables job)
- v2.x: explicit via `maven-command` input ('deploy' or 'verify')

**Restricted Actors:**
- v1.x: checks actor from build-config.yaml, uses 'verify' goal for restricted actors
- v2.x: no actor checks, maven-command specified explicitly

**Docker Build:**
- v1.x: separate workflows for lib (no docker) vs service (with docker)
- v2.x: docker build always runs, auto-skips for fork PRs or missing docker config

**Manual Triggers:**
- v1.x: workflow_dispatch with dry-run option
- v2.x: workflow_dispatch in maven-deploy.yml for deploying from any branch

## Required Configuration

Repository variables:
- `SONAR_PROJECT_KEY` (optional)