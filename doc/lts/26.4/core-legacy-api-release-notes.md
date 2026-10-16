# core-legacy-api: config-server API on Consul

`core-legacy-api` ([qubership-mini-core](https://github.com/Netcracker/qubership-mini-core)) is a Go replacement for
config-server. It serves the same REST API, stores properties in Consul, and reads the properties that config-server
stored there as they are. Microservices keep reading their configuration from the same URLs.

## What stays the same

- The paths of the endpoints that read and write properties, including the `.json`, `.properties`, and `.yaml` ones.
- The Consul key layout: `config/<namespace>/<application>/` and `config/<namespace>/<application>,<profile>/`.
- The Kubernetes Service `config-server`, which selects the `core-legacy-api` pods.

## What changes

- **Storage.** Properties are stored only in Consul. PostgreSQL storage through DBaaS and the `POST /api/v1/migrate`
  endpoint are gone, so the properties have to be in Consul, with config-server running with `CONSUL_ENABLED=true`,
  before config-server is replaced.
- **Kubernetes resources.** The Deployment, pods, container, ServiceAccount, HPA, and PodMonitor are named after
  `SERVICE_NAME`, which defaults to `core-legacy-api`. RBAC bindings, label selectors, Consul ACL binding rules, and
  log or metric filters that use the `config-server` names stop matching.
- **Helm.** The chart has no DBaaS, `CONSUL_ENABLED`, or JVM parameters, and `CONSUL_URL` is required. A removed
  parameter left in the values is ignored without an error. The resource profiles set 80Mi to 100Mi of memory, where
  config-server had 360Mi to 435Mi.
- **API.** The encryption endpoints, `/metrics`, `/health/{component}`, and `/v3/api-docs` are gone. A write of
  conflicting keys such as `db` and `db.url` succeeds instead of returning 400, concurrent writes take no lock, and a
  read while Consul is unreachable returns 500 instead of an empty property source. The `.json`, `.properties`, and
  `.yaml` output lists keys in sorted order.
- **Default properties.** On every start the service adds the missing default properties for `global`,
  `tenant-manager`, and `dmp-tenant-activator`. Properties that are already stored keep their values.
- **Monitoring.** Metrics have other names and labels, so dashboards and alerts built on config-server metrics show
  no data. `/health` no longer checks Consul, and the new `/readiness` endpoint does. Traces go to Zipkin instead of
  an OTLP collector.

## How a deployment switches

The replacement is in place: config-server is uninstalled, and `core-legacy-api` is installed in the same namespace.
The two charts cannot run side by side, because both create the `config-server` Service.

## Documentation

| Page | Covers |
| --- | --- |
| [README](https://github.com/Netcracker/qubership-mini-core/blob/main/README.md) | Property storage in Consul and the deployment parameters |
| [Replacing config-server](https://github.com/Netcracker/qubership-mini-core/blob/main/core-legacy-api/docs/06-replacing-config-server.md) | What a deployment has to check: Kubernetes resources, Consul access, Helm parameters, endpoints, API behavior, metrics, health, and tracing |
| [Removed API](https://github.com/Netcracker/qubership-mini-core/blob/main/core-legacy-api/docs/01-removed-api.md) | Endpoints that config-server has and `core-legacy-api` does not |
| [Startup initialization](https://github.com/Netcracker/qubership-mini-core/blob/main/core-legacy-api/docs/00-startup-initialization.md) | Default properties written at startup |
