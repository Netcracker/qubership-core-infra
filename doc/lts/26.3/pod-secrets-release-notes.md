# Pod Secrets Property Source

## Overview

A new component that reads Kubernetes secrets mounted as files and exposes them as configuration properties.

Each file in the mounted directory (default `/etc/secrets/pod-secrets`) becomes a configuration property

If the directory does not exist (no secrets mounted), the source contributes no properties and the application continues to resolve configuration from other sources as usual.

**Priority:** Consul/config-server > **pod-secrets** > system properties > env vars

---

## Java

**Distribution**

- `pod-secrets-provider-spring` is included in `microservice-framework-common`. Spring Boot applications receive it automatically via `EnvironmentPostProcessor` — no annotation or property needed.
- `pod-secrets-config-source` is registered in `cloud-core-quarkus-bom`. The Quarkus extension activates via `RunTimeConfigBuilderBuildItem` with no additional configuration required.

**Secret rotation**

New values are picked up without a restart via a TTL cache (default 60 s).

**Properties names**

| File          | Exposed as                                        |
|---------------|---------------------------------------------------|
| `db_password` | `db.password` · `db_password` · `DB_PASSWORD`    |
| `API_TOKEN`   | `api.token` · `api_token` · `API_TOKEN`           |

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

**Properties names**

| File          | Exposed as                                        |
|---------------|---------------------------------------------------|
| `db_password` | `db.password`    |
| `API_TOKEN`   | `api.token`      |

**Configuration**

| Env var           | Default                    | Description       |
|-------------------|----------------------------|-------------------|
| `POD_SECRETS_DIR` | `/etc/secrets/pod-secrets` | Secrets directory |


---


## Migrating from environment variables to pod-secrets

If your application previously read credentials from environment variables (e.g. `FOO_USERNAME`, `FOO_PASSWORD`, `BAR_USERNAME`, `BAR_PASSWORD`) and those values came from separate Kubernetes Secrets, you can switch to pod-secrets with no code changes.

**Single secret**

If all credentials live in one Kubernetes Secret, mount it directly with `items` to control file names:

```yaml
volumes:
  - name: app-credentials
    secret:
      secretName: secret
      items:
        - key: username
          path: FOO_USERNAME
        - key: password
          path: FOO_PASSWORD

volumeMounts:
  - name: app-credentials
    mountPath: /etc/secrets/pod-secrets
    readOnly: true
```

**Multiple secrets: the problem with naively mounting two secrets to the same directory**

Mounting two volumes to the same `mountPath` causes the second to shadow the first — only the files from the second secret will be visible at runtime.

**Solution: use a `projected` volume**

A `projected` volume merges multiple secrets into a single directory. Combined with `items`, you can map each secret key to a file whose name matches the original environment variable — so `configloader.GetOrDefaultString("foo.username", "")` continues to work without any changes in the application code.

```yaml
volumes:
  - name: app-credentials
    projected:
      sources:
        - secret:
            name: first-secret
            items:
              - key: username
                path: FOO_USERNAME
              - key: password
                path: FOO_PASSWORD
        - secret:
            name: second-secret
            items:
              - key: username
                path: BAR_USERNAME
              - key: password
                path: BAR_PASSWORD

volumeMounts:
  - name: app-credentials
    mountPath: /etc/secrets/pod-secrets
    readOnly: true
```

At runtime the directory contains four files (`FOO_USERNAME`, `FOO_PASSWORD`, `BAR_USERNAME`, `BAR_PASSWORD`), and the property source exposes them as `foo.username`, `foo.password`, `bar.username`, `bar.password` — exactly the keys the application already uses.
