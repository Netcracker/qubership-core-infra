# Cloud Core databases through the DBaaS Operator

control-plane, config-server, and site-management can get their PostgreSQL databases from the DBaaS Operator. Each
chart declares the database as an `InternalDatabase`, requests its credentials with a `DatabaseSecretClaim`, and mounts
the Secret the operator writes. The service reads the database from that Secret instead of creating it over the DBaaS
REST API.

Both modes stay supported. The same chart release installs on a cluster with the DBaaS Operator and on one without it,
and on the second it renders exactly as before. The work and its pull requests are tracked in
[Netcracker/qubership-core-infra#395](https://github.com/Netcracker/qubership-core-infra/issues/395).

## Two modes, one chart

The mode follows the cluster. Every chart checks at render time whether the cluster serves `dbaas.netcracker.com/v1`,
the API group of the DBaaS Operator CRDs. There is no deployment parameter to set or forget.

| | DBaaS Operator mode | Legacy mode |
|---|---|---|
| Selected when | The cluster serves `dbaas.netcracker.com/v1` | It does not |
| Database created by | The DBaaS Operator, from `InternalDatabase` | The service, or core-bootstrap for control-plane, over REST |
| Credentials | Secret written by the operator, mounted read-only | Returned by the REST call |
| Access policy | `DatabaseAccessPolicy` | Legacy `DBaaS` / `subKind: DbPolicy` declaration |
| Rendered chart | Operator resources, the Secret mount, mode settings | Identical to the previous release |

Keeping legacy mode byte-identical is a requirement, not a side effect: a cluster without the operator must see no new
resource, no new environment variable, and no rollout caused by this change.

## How the manifests were produced

The operator resources were generated with two skills published in
[qubership-dbaas](https://github.com/Netcracker/qubership-dbaas/tree/main/dbaas-operator/agent-packages):

- `dbaas-mounted-secret-migration` turns the database a service requests at runtime into an `InternalDatabase` and a
  `DatabaseSecretClaim`, plus the Secret volume and mount. Its validator, `validate_generated.py`, checks that the
  classifier, type, and role on the claim match what the service's DBaaS client requests. They must match exactly:
  the client finds the mounted Secret by that key, and a mismatch silently falls back to REST.
- `migrate-core-operator-dbaas-declarations` converts the legacy `DbPolicy` declarations of config-server and
  site-management to `DatabaseAccessPolicy`.

The skills produce operator-only manifests. The capability guards that keep legacy mode working were added by hand,
the same way in every chart.

## Helm template changes

### Shared templates in coretpl 0.2.4

Two templates in the `coretpl` library give every chart the same condition and the same operator namespace:

```yaml
{{- if include "coretpl.dbaasOperatorEnabled" $ }}
apiVersion: dbaas.netcracker.com/v1
kind: InternalDatabase
...
spec:
  operatorNamespace: {{ include "coretpl.dbaasNamespace" $ | quote }}
{{- end }}
```

- `coretpl.dbaasOperatorEnabled` returns `true` where the cluster serves `dbaas.netcracker.com/v1`, and an empty
  string elsewhere, so `if not (...)` selects the legacy branch.
- `coretpl.dbaasNamespace` returns the namespace of the DBaaS installation, taken from
  `API_DBAAS_ADDRESS` (`http://<aggregator>.<namespace>[.<domain>]:<port>`). The render fails, naming
  `API_DBAAS_ADDRESS`, when the second label of the host is missing, empty, or all digits, as in an IP address.

Call both with the root context, `$`, so they also work inside `range` and `with`.

### What each service chart adds

| Template | Content | Rendered in |
|---|---|---|
| `internaldatabase-dbaas.yaml` | The service's `InternalDatabase` | Operator mode |
| `databasesecretclaim-dbaas.yaml` | The `DatabaseSecretClaim` and the Secret name | Operator mode |
| `dbpolicy-dbaas.yaml` | `DatabaseAccessPolicy`, or the unchanged legacy `DbPolicy` in the `else` branch | Both, one form each |
| `deployment.yaml` | A Secret volume and a read-only mount at `/etc/secrets/dbaas-secrets/<secretName>` | Operator mode |

`dbpolicy-dbaas.yaml` exists in config-server and site-management, which had a legacy `DbPolicy`; control-plane has
none.

Service-specific additions to `deployment.yaml`:

- control-plane sets `DBAAS_OPERATOR_ENABLED: 'true'` and leaves out the legacy `pod-secrets` volume, both from the
  same condition as the Secret volume.
- site-management sets `MICROSERVICE_NAME` from `SERVICE_NAME`, so the runtime request carries the identity the CRs
  declare.

`cloud-core-configuration` in core-bootstrap adds a `dbaas-operator-secrets` Role and RoleBinding, which let the
operator write claim Secrets in the Core namespace, and `DBAAS_OPERATOR_ENABLED: "true"` in the core-bootstrap Secret.
It takes `coretpl` from its own repository (`file://../../coretpl`).

### The Secret volume is required

The volume is not `optional`. With an optional volume the pod could start before the operator writes the Secret: the
client would miss the mounted lookup, fall back to REST, and cache that result for the life of the process. A required
volume holds the first rollout in `ContainerCreating` until the Secret exists, which is expected.

## Runtime changes

- **config-server and site-management:** none. Their DBaaS clients already look up the mounted Secret first and keep
  the REST call as the fallback.
- **control-plane:** its own pool provider answered from core-bootstrap's credentials and hid the mounted Secret. With
  `DBAAS_OPERATOR_ENABLED=true` it registers no provider, so the client reads the Secret, and it neither reads
  `DB_CREDENTIALS_SECRET` nor starts the pod-secrets watcher.
- **core-bootstrap:** with `DBAAS_OPERATOR_ENABLED=true` it does not create control-plane's database or its
  `control-plane-db-credentials` Secret.

## Tests

The `DBaaS Operator integration tests` workflow in qubership-core-test-apps installs Cloud Core twice: from scratch on
a cluster with the operator, and as an upgrade from a REST installation. An Istio waypoint denies the DBaaS REST API
to the Core namespace and counts the denials. Both scenarios require every resource `Ready`, every service running on
its Secret, the existing databases adopted, and no REST request from any Core workload.

The integration tests report also runs the existing suites on Core mesh with the operator enabled, next to the run
without it.
