# Consul login with the Kubernetes projected token

The Consul config provider libraries obtain the Consul ACL token by exchanging the pod's Kubernetes projected service
account token. The previous exchange of an M2M token stays in place, and a pod falls back to it on its own while the
Kubernetes side is not ready yet. Nothing in the way a microservice reads Consul properties changes.

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
| `kubernetes-with-m2m-fallback` | Default. Logs in with the projected token. On failure the pod keeps working through the M2M exchange and tries the projected token again later. The first success switches the pod over for good. |
| `kubernetes` | Logs in with the projected token only. There is no fallback. |
| `m2m` | Logs in with the M2M token only. This is the behavior of the previous library versions. |

An unknown mode, and a login failure the retries do not fix, keep the microservice from starting. A failsafe Go
property source starts without Consul properties instead, as it already does for other Consul failures.

The retry of the projected token rides on the scheduled relogin rather than on a timer of its own, so
`CONSUL_AUTH_FALLBACK_RECHECK_INTERVAL` is a lower bound on how often a pod that fell back retries, not the period. A
pod holding a token with no expiration time never relogs in, and keeps the way it picked until it restarts.

## Login settings

| Environment variable | Default | Controls |
|---|---|---|
| `CONSUL_AUTH_MODE` | `kubernetes-with-m2m-fallback` | The way the ACL token is obtained |
| `CONSUL_AUTH_METHOD` | `applications-k8s-m2m` | Name of the Consul auth method the projected token is presented to |
| `CONSUL_AUTH_AUDIENCE` | `netcracker` | Audience of the projected token the pod sends |
| `CONSUL_AUTH_FALLBACK_RECHECK_INTERVAL` | `5h` | Lower bound on how often a pod that fell back retries the projected token |

The settings come from the environment and cannot be kept in Consul: the library needs them before it has the token
that reading Consul requires.

The names are the same on all three stacks, so one variable configures a Spring, a Quarkus, and a Go microservice
alike. Write the interval with a unit, as `5h` or `30m`: that form works on all three stacks, while `PT5H` and a bare
number do not.

The defaults match what the platform registers. Set `CONSUL_AUTH_METHOD` or `CONSUL_AUTH_AUDIENCE` only if your
installation names them differently.

## The relogin schedule

A pod relogs in at 80% of the remaining lifetime of its token, where the previous library versions used a fixed
interval before expiry. The period follows the current token, so a pod that moves to the projected token picks up the
`MaxTokenTTL` of the new auth method without a restart.

A failed relogin is retried instead of waiting out the whole period: the delay starts at 10 seconds, doubles on each
consecutive failure up to 5 minutes, and returns to 10 seconds once a relogin succeeds. A pod that cannot reach Consul
therefore retries at most once every 5 minutes, which is a single login request on Go and up to 10 on Spring and
Quarkus. Before this change the pod stayed quiet and kept serving with the token it already held until that token
expired.

## What to change in your service

Nothing is required. A microservice that only updates the library gets `kubernetes-with-m2m-fallback` with the default
auth method name and audience, logs in with the projected token where the platform is ready, and keeps working through
the M2M exchange where it is not. To keep the previous behavior instead, set `CONSUL_AUTH_MODE=m2m`; the M2M way is
kept for the migration and is removed once it is over.

What is recommended is to make the four settings settable per environment, so that a mode can be pinned or an auth
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

A parameter nobody sets renders as an empty value, which the libraries treat as unset, so the defaults above apply
until someone fills a parameter in.

Two things belong to the platform rather than to the microservice, and the projected token needs both: a projected
volume with a token of the audience from `CONSUL_AUTH_AUDIENCE`, `netcracker` by default, mounted so that the token
lands at `/var/run/secrets/tokens/<audience>/token`, and a Consul auth method named as in `CONSUL_AUTH_METHOD`,
`applications-k8s-m2m` by default, whose binding rules grant the microservice its policies. Until both are in place,
the default mode keeps the microservice running through the M2M fallback.

Once a pod migrates, its policies come from the binding rules of the new auth method, which need not grant what the
M2M one granted. In the default mode a pod migrates on its own as soon as the platform side is ready, so this reaches
every microservice that takes the upgrade, not only one that pins `CONSUL_AUTH_MODE=kubernetes`. Check the binding
rules cover what your microservice reads and writes in Consul.

## The supported way to use the Consul token

The libraries publish the ACL token through one object per stack, and that object is the only supported source of it.

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

In Go it is the client from `consul-propertysource`:

```go
c := consul.NewClient(consul.ClientConfig{Address: "<consul-url>", Namespace: "<namespace>"})
if err := c.Login(); err != nil {
    return err
}
token := c.SecretId()
```

A token obtained any other way carries none of the behavior described here: no mode selection, no fallback, and no
scheduled relogin. The public API of both libraries changed in this release. Depend on the objects above and on
nothing else: the implementation classes behind them are not part of the contract and change without notice.

## What the log shows

On Spring and on Quarkus every login attempt logs the auth method it goes to, at `INFO`. Go logs the first login, the
fallback, and the switch. On both, the fallback decision is logged once rather than on every retry, and a pod that
started on the fallback and later moved over logs one more record, which is how a completed migration is visible. The
bearer token, the ACL token, and the body of a successful login response are never logged.

The wording differs between the stacks. On Spring and on Quarkus:

```text
Perform login to http://consul:8500 with applications-k8s-m2m auth method
Consul ACL token is obtained by the kubernetes auth method
Consul login by the kubernetes auth method failed, falling back to the m2m one and retrying it every PT5H: <reason>
Consul ACL token is obtained by the kubernetes auth method from now on, the fallback to the m2m one is over
Error occurred during getting new consul token. Will try in 20 seconds.
```

On Go:

```text
Logged in to Consul with auth method 'applications-k8s-m2m'
Consul login with auth method 'applications-k8s-m2m' failed: <reason>. Falling back to auth method '<m2m auth method>'
Consul login with auth method 'applications-k8s-m2m' succeeded. Fallback disabled
failed to refresh Consul token: <reason>. Next attempt in 20s
```
