# Pod Secrets Property Source

## Overview

A new component that automatically reads Kubernetes secrets mounted as files and exposes them as configuration properties — no changes to application code required.

Each file in the mounted directory (default `/etc/secrets/pod-secrets`) becomes a configuration property:

| File          | Exposed as                                        |
|---------------|---------------------------------------------------|
| `db_password` | `db.password` · `db_password` · `DB_PASSWORD`    |
| `API_TOKEN`   | `api.token` · `api_token` · `API_TOKEN`           |

If the directory does not exist (no secrets mounted), the source contributes no properties and the application continues to resolve configuration from other sources as usual.

**Priority:** Consul/config-server > **pod-secrets** > system properties > env vars

---

## Java

**Distribution**

- `pod-secrets-provider-spring` is included in `microservice-framework-common`. Spring Boot applications receive it automatically via `EnvironmentPostProcessor` — no annotation or property needed.
- `pod-secrets-config-source` is registered in `cloud-core-quarkus-bom`. The Quarkus extension activates via `RunTimeConfigBuilderBuildItem` with no additional configuration required.

**Secret rotation**

New values are picked up without a restart via a TTL cache (default 60 s).

**Configuration**

| Property              | Default                    | Description                 |
|-----------------------|----------------------------|-----------------------------|
| `pod.secrets.dir`     | `/etc/secrets/pod-secrets` | Secrets directory           |
| `pod.secrets.ttl`     | `PT60S`                    | Cache TTL (ISO-8601)        |
| `pod.secrets.enabled` | `true`                     | Set `false` to disable      |

The directory can also be set via the `POD_SECRETS_DIR` environment variable.

**Priority (Quarkus ordinal)**

| Source          | Ordinal |
|-----------------|---------|
| Consul          | 500     |
| **pod-secrets** | **450** |
| System props    | 400     |
| Env vars        | 300     |

---

## Go

**Distribution**

Package `podsecrets-propertysource` in `qubership-core-lib-go-rest-utils`. Wired up explicitly:

```go
import (
    "github.com/netcracker/qubership-core-lib-go/v3/configloader"
    podsecrets "github.com/netcracker/qubership-core-lib-go-rest-utils/v2/podsecrets-propertysource"
)

func init() {
    configloader.InitWithSourcesArray(
        podsecrets.AddPodSecretsPropertySource(configloader.BasePropertySources()),
    )
}
```

When using Consul, add pod-secrets **before** it so Consul takes precedence:

```go
sources = podsecrets.AddPodSecretsPropertySource(sources)
sources = configserver.AddConfigServerPropertySource(sources)
```

**Secret rotation**

New values are picked up without a restart via an fsnotify watcher:

```go
watcher, err := podsecrets.StartWatcher()
defer watcher.Stop()
```

**Configuration**

| Env var           | Default                    | Description       |
|-------------------|----------------------------|-------------------|
| `POD_SECRETS_DIR` | `/etc/secrets/pod-secrets` | Secrets directory |

Key normalisation: file names are lowercased and `_` is replaced with `.` (`db_password` → `db.password`).
