# Consul login with the Kubernetes projected token

The Consul config provider libraries obtain the Consul ACL token by exchanging the pod's Kubernetes projected service
account token for it. The previous exchange of an M2M token stays in place, and a pod falls back to it on its own
while the platform side is not ready yet. Nothing in the way a microservice reads Consul properties changes.

| Stack | Artifact | Version |
|---|---|---|
| Spring, RestTemplate | `com.netcracker.cloud:consul-config-provider-spring-resttemplate` | TBD |
| Spring, WebClient | `com.netcracker.cloud:consul-config-provider-spring-webclient` | TBD |
| Quarkus | `com.netcracker.cloud.quarkus:consul-client` | TBD |
| Go | `github.com/netcracker/qubership-core-lib-go-rest-utils/v2/consul-propertysource` | TBD |

## Login modes

The mode is set by `CONSUL_AUTH_MODE` and read at startup, so it can be changed without rebuilding the microservice.

| Mode | Behavior |
|---|---|
| `kubernetes-with-m2m-fallback` | Default. Logs in with the projected token. On failure the pod keeps working through the M2M exchange and tries the projected token again later. The first success migrates the pod for good. |
| `kubernetes` | Logs in with the projected token only. There is no fallback. |
| `m2m` | Logs in with the M2M token only, the way the previous versions did. The login schedule below still applies. |

An unknown mode keeps the microservice from starting, and so does a login failure the retries do not fix, unless the
Go property source is configured to tolerate Consul failures.

## Login settings

| Environment variable | Default | Controls |
|---|---|---|
| `CONSUL_AUTH_MODE` | `kubernetes-with-m2m-fallback` | The way the ACL token is obtained |
| `CONSUL_AUTH_METHOD` | `applications-k8s-m2m` | Name of the Consul auth method the projected token is presented to |
| `CONSUL_AUTH_AUDIENCE` | `netcracker` | Audience of the projected token the pod sends |
| `CONSUL_AUTH_FALLBACK_RECHECK_INTERVAL` | `5h` | Lower bound on how often the fallback retries the projected token |

The settings come from the environment and cannot be kept in Consul: the library needs them before it has the token
that reading Consul requires.

The names are the same on every stack, so one variable configures a microservice whatever it is written in. Write the
interval with a unit, as `5h` or `30m`: that form works on all three stacks, while `PT5H` and a bare number do not.

The defaults match what the platform registers, so set `CONSUL_AUTH_METHOD` or `CONSUL_AUTH_AUDIENCE` only if your
installation names them differently.

## The login schedule

A pod logs in again at 80% of the remaining lifetime of its token, where the previous versions used a fixed interval
before expiry. The schedule follows the current token, so a pod that migrates picks up the `MaxTokenTTL` of the new
auth method without a restart.

A failed login is retried rather than waiting out the whole schedule, with a delay that grows to at most 5 minutes. Before this change
the pod stayed quiet and kept serving with the token it already held until that token expired.

A pod that fell back retries the projected token during its next scheduled login, not on a timer of its own, so
`CONSUL_AUTH_FALLBACK_RECHECK_INTERVAL` is a lower bound on how often it retries rather than the period. The period
comes from the `MaxTokenTTL` of the auth method the pod logged in to. Where that auth method has no `MaxTokenTTL`,
Consul issues a token that never expires and the pod never logs in again, so a fleet in the default mode stays on the
M2M exchange until its pods are restarted.

## What to change in your service

Nothing is required. A microservice that only updates the library gets `kubernetes-with-m2m-fallback` and the defaults
above. To keep the previous behavior instead, set `CONSUL_AUTH_MODE=m2m`. The M2M exchange is kept for the migration
and will be removed in a later release, once the migration is complete.

What is recommended is to make the four settings configurable per environment, so that a mode can be pinned or an auth
method name corrected without a new build. Add four deployment parameters to the microservice descriptor:

```yaml
env:
  - name: CONSUL_AUTH_MODE
    value: "{{ .Values.CONSUL_AUTH_MODE }}"
  - name: CONSUL_AUTH_METHOD
    value: "{{ .Values.CONSUL_AUTH_METHOD }}"
  - name: CONSUL_AUTH_AUDIENCE
    value: "{{ .Values.CONSUL_AUTH_AUDIENCE }}"
  - name: CONSUL_AUTH_FALLBACK_RECHECK_INTERVAL
    value: "{{ .Values.CONSUL_AUTH_FALLBACK_RECHECK_INTERVAL }}"
```

A parameter nobody sets renders as an empty value, which the libraries treat as unset, so the defaults above apply.

Two things belong to the platform rather than to the microservice, and the projected token needs both:

- a projected volume mounted so that the token of the audience from `CONSUL_AUTH_AUDIENCE` lands at
  `/var/run/secrets/tokens/<audience>/token`;
- a Consul auth method of type `jwt` named as in `CONSUL_AUTH_METHOD`, whose `BoundAudiences` cover
  `CONSUL_AUTH_AUDIENCE` and whose binding rules grant the microservice its policies.

Once a pod migrates, its policies come from the binding rules of the new auth method, which need not grant what the
M2M one granted. In the default mode a pod migrates on its own, so this reaches every microservice that takes the
upgrade, not only one that pins `CONSUL_AUTH_MODE=kubernetes`. Check that the binding rules cover what your
microservice reads and writes in Consul. Narrower rules do not fail the login: the pod migrates, keeps the token, and
Consul starts answering its reads and writes with `403`. Recover by restarting the pod with `CONSUL_AUTH_MODE=m2m`.

## The supported way to use the Consul token

Each library publishes the ACL token through one object, and that object is the only supported source of it.

On Spring and on Quarkus it is the `TokenStorage` bean, and `TokenStorage.get()` returns the current ACL token.

On Spring:

```java
private final TokenStorage tokenStorage;

public MyService(TokenStorage tokenStorage) {
    this.tokenStorage = tokenStorage;
}
```

On Quarkus:

```java
@Inject
TokenStorage tokenStorage;
```

In Go it is the client from `consul-propertysource`. A microservice that only reads properties through
`NewPropertySource` never handles the token itself; build a client where the microservice talks to Consul on its own:

```go
c := consul.NewClient(consul.ClientConfig{Address: "<consul-url>", Namespace: "<namespace>"})
if err := c.Login(); err != nil {
    return err
}
token := c.SecretId()
```

The client and the property source log in independently, so a Go microservice that uses both holds two ACL tokens.

A token obtained any other way carries none of the behavior described here: no mode selection, no fallback, and no
scheduled login. The public API changed on Spring and Quarkus in this release; depend on the objects above and on
nothing else, because the implementation classes behind them are not part of the contract and change without notice.

## What the log shows

On Spring and on Quarkus every login attempt logs the auth method it goes to, at `INFO`. Go logs the first login, the
fallback, and the switch. On both, the fallback decision is logged once rather than on every retry, and a pod that
started on the fallback and later migrated logs one more record, which is how a completed migration is visible. The
bearer token, the ACL token, and the body of a successful login response are never logged.

On Spring and on Quarkus:

```text
Perform login to http://consul:8500 with applications-k8s-m2m auth method
Consul ACL token is obtained by the kubernetes way
Consul ACL token is obtained by the kubernetes way from now on, the fallback to the m2m one is over
```

On Go:

```text
Logged in to Consul with auth method 'applications-k8s-m2m'
Consul login with auth method 'applications-k8s-m2m' failed: <reason>. Falling back to auth method '<m2m auth method>'
Consul login with auth method 'applications-k8s-m2m' succeeded. Fallback disabled
```
