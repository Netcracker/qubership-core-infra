# Consul login with the ServiceAccount token and rights through ConsulACL CRs

Cloud Core services obtain their Consul ACL token by logging in to Consul with the k8s token, the projected token of
their ServiceAccount with the audience `netcracker`. The rights of the ACL token come from Consul roles and policies
that the Helm charts of Cloud Core declare in ConsulACL CRs. The Consul ACL Configurator, the operator from the
qubership-consul repository, processes the ConsulACL CRs and creates the roles, policies, and binding rules in Consul.

Before:

- At each Cloud Core deployment, core-bootstrap created policies and an ACL token that never expires, and stored the
  token in the Secret `config-server-consul-token`. For that it needed the Consul admin token: an ACL token with the
  right to create policies and tokens, passed in the deployment parameter `CONSUL_ADMIN_TOKEN`.
- config-server used that token, and after its replacement core-legacy-api from the qubership-mini-core repository
  uses it.
- The other services logged in to Consul with the M2M token through a preconfigured auth method.

> [!WARNING]
> config-server is deprecated, replaced by core-legacy-api, and no longer delivered.

Now, in the `hybrid` and `k8s` modes:

- A service logs in with the k8s token through the JWT auth method `applications-k8s-m2m`, which the Consul ACL
  Configurator creates.
- Consul issues an ACL token with the role `<namespace>_<ServiceAccount name>_v2`, and the policies of that role
  define the rights of the token. The token is valid for 30 minutes, and the client logs in again before it expires.
- The roles and policies are declared in ConsulACL CRs in the Cloud Core charts, see
  [Rights in Consul](#rights-in-consul).
- The static ACL token for core-legacy-api stays for the migration period. core-bootstrap decides whether to issue it
  by the auth mode, see [Consul auth modes](#consul-auth-modes).
- Cloud Core also works on Consul with ACLs disabled, which matters mostly for CI tests and dev stands. Such a Consul
  answers the login with `401` and the body `ACL support disabled`, and the Go and Java Consul clients then call it
  without an ACL token.

Task: [qubership-core-infra#426](https://github.com/Netcracker/qubership-core-infra/issues/426).

## Consul auth modes

The mode is set by the Cloud Core deployment parameter `M2M_AUTH_MODE`, which also sets the mode of the M2M calls
between services. The deployment parameter `CONSUL_AUTH_MODE` overrides the mode for Consul only and is meant for
tests.

| Mode | core-bootstrap | core-legacy-api | Other Cloud Core services |
|---|---|---|---|
| `legacy` (default) | Issues a static ACL token with the Consul admin token and stores it in the Secret `config-server-consul-token` | Static ACL token from the Secret `config-server-consul-token` | Login with the M2M token |
| `hybrid` | Asks Consul for the auth method `applications-k8s-m2m`. If the method exists, deletes the Secret; if it does not, issues the static ACL token as in `legacy` | Static ACL token if the Secret exists, otherwise login with the k8s token. The choice is made at pod start | Login with the k8s token through `applications-k8s-m2m`; if the login fails, with the M2M token |
| `k8s` | Issues nothing and deletes the Secret; the Consul admin token is not needed | Login with the k8s token only | Login with the k8s token only |

The ConsulACL and ConsulKV CRs are installed in the `hybrid` and `k8s` modes when the cluster has the ConsulACL CRD.
In `legacy` there are none.

## Rights in Consul

The rights of a service are set in three places:

- **The shared chart `cloud-core-configuration`** from the qubership-core-bootstrap repository. It holds the common
  settings of Cloud Core: it is installed before the service charts and runs the core-bootstrap pre-deploy tasks. It
  contains the ConsulACL CR `cloud-core` with one binding rule for the auth method `applications-k8s-m2m`: an ACL token
  obtained with the k8s token of a ServiceAccount from the Cloud Core namespace gets the role
  `<namespace>_<ServiceAccount name>_v2`. The ServiceAccount `default` gets no role.
- **The service template in the Helm library coretpl** from the qubership-core-bootstrap repository. The chart of
  every Cloud Core service includes the coretpl templates, and the template creates a ConsulACL CR with the role
  `<namespace>_<SERVICE_NAME>_v2` and its policies. The role is bound to the ServiceAccount name, so a service gets
  these rights only if its pod runs under the ServiceAccount named `SERVICE_NAME`.
- **The service's own ConsulACL CR.** A service that needs other rights turns the coretpl template off with the
  deployment parameter `CORE_TPL_MS_CONSULACL_ENABLED: false` and installs a ConsulACL CR with the role
  `<namespace>_<SERVICE_NAME>_v2` in its own chart. core-legacy-api and core-operator do so.

Together with the ConsulACL CR of a service, its chart installs a ConsulKV CR. From it the Consul ACL Configurator
creates the empty folder keys of the service in Consul KV: `config/<namespace>/<SERVICE_NAME>/`,
`data/<namespace>/<SERVICE_NAME>/`, and `logging/<namespace>/<SERVICE_NAME>/`. The ConsulKV CR of the
`cloud-core-configuration` chart creates the common folders of the namespace: `config/<namespace>/application/`,
`data/<namespace>/`, `bluegreen/<ORIGIN_NAMESPACE>/`, and `composite/<Composite ID>/`.

Rights of the roles:

- **The role from the coretpl template:**
  - write: `config/<namespace>/<SERVICE_NAME>/`, `data/<namespace>/`;
  - read: `config/<namespace>/application/`, `logging/<namespace>/<SERVICE_NAME>/`, `bluegreen/`,
    `composite/<Composite ID>/`;
  - write sessions: `session_prefix ""`.
- **core-legacy-api** (repository qubership-mini-core, chart `core-legacy-api`): as the coretpl role, but write on all
  of `config/<namespace>/`, because core-legacy-api writes the properties of every service of the namespace.
- **core-operator** (repository qubership-core-core-operator, chart `core-operator`): as the coretpl role, plus write
  on `composite/<Composite ID>/` and `config/<namespace>/application/composite/`, because core-operator writes the
  Composite structure.

## Requirements for `hybrid` and `k8s`

- qubership-consul 0.15.6 or later with the Consul ACL Configurator. The Consul ACL Configurator is on by default
  (`consulAclConfigurator.enabled: true`) and creates the auth method `applications-k8s-m2m`. The ConsulACL and
  ConsulKV CRDs come with the qubership-consul chart.
- The deployment parameter `CONSUL_URL` holds the in-cluster address of Consul in the form
  `http://<Consul service>.<Consul namespace>:<port>`. From the namespace in this address the chart sets in the
  ConsulACL CR which Consul ACL Configurator instance processes it.
- Every service that calls Consul runs under the ServiceAccount named `SERVICE_NAME`, and its pod mounts the k8s token
  of that ServiceAccount with the audience `netcracker`.

## Changes by repository

### qubership-core-lib-go-rest-utils, [#126](https://github.com/Netcracker/qubership-core-lib-go-rest-utils/pull/126)

- The package `consul-propertysource` gets the function `ReadAuthSettings()`. It returns the mode, auth method, and
  audience of the Consul login the way the Consul client of the library resolves them. core-legacy-api and
  core-bootstrap use it to resolve the mode by the same rules as the client.
- The client takes the login answer `401` with the body `ACL support disabled` as a login without an ACL token and logs
  `Consul ACLs are disabled: working without a Consul token`. Without this, Go services with the Consul login would
  not work on Consul with ACLs disabled.

### qubership-core-java-libs, [#251](https://github.com/Netcracker/qubership-core-java-libs/pull/251)

- `LoginTokenProvider` returns an empty ACL token without expiry for the login answer `401` with the body
  `ACL support disabled`. Without this, core-operator, which has the Consul login on, would not start on Consul with
  ACLs disabled.
- `ConsulRestClient.login` returns an error answer of Consul instead of throwing `IOException`. This lets Spring
  services with the Consul login work on Consul with ACLs disabled too: before, the answer
  `401 ACL support disabled` became a login error, and the service did not start.

### qubership-core-bootstrap, [#265](https://github.com/Netcracker/qubership-core-bootstrap/pull/265)

- The pre-deploy task `configserver` issues the static ACL token or deletes the Secret `config-server-consul-token` by
  the mode, see [Consul auth modes](#consul-auth-modes). In `k8s` it does not need the Consul admin token. The RBAC
  Role of this task in the `cloud-core-configuration` chart may now delete the Secret `config-server-consul-token`.
- The `cloud-core-configuration` chart installs the ConsulACL CR `cloud-core` with the binding rule and a ConsulKV CR
  with the common folders of the namespace.
- coretpl 0.2.5: the ConsulACL and ConsulKV CR template of a service, the deployment parameter
  `CORE_TPL_MS_CONSULACL_ENABLED`, and the helper templates `consulacl.enabled`, `consulacl.operatorNamespace`, and
  `coretpl.consulAuthMode` for charts with their own ConsulACL CR.
- cloud-core-local-dev installs qubership-consul 0.15.6.
- On stands with ACLs (`CONSUL_ACLS=true`), cloud-core-local-dev runs the `configserver` task and passes it the admin
  token of the installed Consul. Before, the task did not run on these stands, so CI did not check issuing the static
  ACL token or deleting the Secret `config-server-consul-token`.
- cloud-core-local-dev installs core-legacy-api instead of config-server by default.

### qubership-mini-core (core-legacy-api), [#19](https://github.com/Netcracker/qubership-mini-core/pull/19)

- The ACL token for Consul requests is chosen by the mode: the static token from the Secret
  `config-server-consul-token` or a login with the k8s token through `applications-k8s-m2m`.
- Right after the installation the login can fail until the Consul ACL Configurator has processed the ConsulACL CR of
  the service. So before the HTTP server starts, the service retries the login and the write of the default properties
  every 5 seconds instead of failing.
- The ACL token is valid for 30 minutes, and the client logs in again from time to time. core-legacy-api takes the
  current token for every Consul request, so after a new login the requests carry the new token.
- The chart turns the coretpl template off and installs its own ConsulACL CR. In the `k8s` mode the pod does not mount
  the Secret `config-server-consul-token`.

### qubership-core-core-operator, [#241](https://github.com/Netcracker/qubership-core-core-operator/pull/241)

- The temporary setting `quarkus.consul-source-config.m2m.enabled=false` is removed from `application.properties`.
  With it, core-operator called Consul without an ACL token and could not write the Composite structure on Consul with
  ACLs. Now it logs in to Consul by the mode, as the other services do.
- The chart turns the coretpl template off and installs its own ConsulACL CR.

### Other Cloud Core services

[paas-mediation#327](https://github.com/Netcracker/qubership-core-paas-mediation/pull/327),
[control-plane#496](https://github.com/Netcracker/qubership-core-control-plane/pull/496),
[dbaas-agent#255](https://github.com/Netcracker/qubership-core-dbaas-agent/pull/255),
[facade-operator#321](https://github.com/Netcracker/qubership-core-facade-operator/pull/321),
[maas-agent#281](https://github.com/Netcracker/qubership-core-maas-agent/pull/281),
[site-management#335](https://github.com/Netcracker/qubership-core-site-management/pull/335):

- rest-utils with the changes of
  [qubership-core-lib-go-rest-utils#126](https://github.com/Netcracker/qubership-core-lib-go-rest-utils/pull/126);
- coretpl 0.2.5: the service chart gets its ConsulACL CR from the template.

### qubership-core-test-apps, [#251](https://github.com/Netcracker/qubership-core-test-apps/pull/251)

- `core-legacy-api-integration-tests` run on three stands: Consul without ACLs in `legacy`, Consul with ACLs in `k8s`
  and in `hybrid`.
- On the stands with ACLs, `core-legacy-api-integration-tests` check that the Secret `config-server-consul-token` is
  absent, that is, core-bootstrap issued no static ACL token.
- The mesh test services (Go, Quarkus, Spring) log in to Consul with the k8s token and get their ConsulACL CR from
  coretpl; each runs under its own ServiceAccount.
- The `integration-tests` workflow takes the parameter `consul-acls`; `m2m-mode-combinations` checks `hybrid` and
  `k8s` on Consul with ACLs; the nightly report adds the run `run-tests-core-mesh-consul-acl`.
- The stands install core-legacy-api instead of config-server. The exception is
  `config-server-migration-integration-tests`: it starts from config-server and checks its replacement with
  core-legacy-api.

## Limitations

- core-legacy-api in `hybrid` chooses between the static ACL token and the login with the k8s token only at pod start.
  When core-bootstrap deletes the Secret, it does not revoke the token in Consul, so a running pod keeps working until
  it restarts.
- Deleting from Consul the old static ACL tokens and the policies `<namespace>_config-edit` and
  `<namespace>_config-server_logging`, which core-bootstrap created in `legacy`, is out of scope.
