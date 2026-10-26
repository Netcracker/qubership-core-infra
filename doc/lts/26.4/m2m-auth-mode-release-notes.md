# M2M calls with the Kubernetes projected token

Starting with release 26.4, Cloud Core services can authenticate their machine-to-machine (M2M) calls with the pod's
Kubernetes projected service account token instead of the legacy M2M token from the application's token provider. With
the Kubernetes token, a service calls DBaaS and MaaS directly rather than through dbaas-agent and maas-agent. The
`M2M_AUTH_MODE` environment variable selects the mode. After an upgrade, M2M calls stay in the default `legacy` mode.

## Modes

| Mode | Token the service sends | DBaaS and MaaS calls go to |
| --- | --- | --- |
| `legacy` (default) | Legacy M2M token | dbaas-agent and maas-agent |
| `hybrid` | Kubernetes token; the legacy M2M token after a [fallback](#fallback-in-hybrid) | DBaaS and MaaS directly; the agent after a fallback or when the direct address is not set |
| `k8s` | Kubernetes token only, no fallback | DBaaS and MaaS directly; the direct address is required |

`hybrid` is the migration mode. The service still needs the legacy token provider and the agents, as in `legacy`, and
uses the Kubernetes token and the direct addresses where they are available. In `k8s` it needs neither the legacy token
provider nor the agents.

An unset or empty variable means `legacy`, and any value other than the three modes stops the service at startup. The
libraries read the variable from the environment only, so it cannot be set in application properties.

## How a call is authenticated

Kubernetes issues a short-lived token for the pod's service account and projects it into the container, one token per
audience. The client library reads the token of the receiver's audience and sends it as `Authorization: Bearer
<token>`:

| Receiver | Audience | Token file |
| --- | --- | --- |
| Cloud Core and application services | `netcracker` | `/var/run/secrets/tokens/netcracker/token` |
| DBaaS | `dbaas` | `/var/run/secrets/tokens/dbaas/token` |
| MaaS | `maas` | `/var/run/secrets/tokens/maas/token` |

The receiver verifies the token signature against the cluster's OIDC discovery document and JWKS, checks the audience,
and identifies the caller by its service account and namespace.

### Fallback in `hybrid`

A client in `hybrid` sends the legacy M2M token instead of the Kubernetes token in two cases:

- the Kubernetes token file cannot be read;
- the receiver answers `401` to the Kubernetes token. The client then resends the request with the legacy token.

A `403`, a `5xx`, or a network error does not trigger the fallback and is returned to the caller. DBaaS and MaaS
clients send the fallback request to dbaas-agent or maas-agent.

After a successful fallback, the client caches the use of the legacy token for 5 hours. Java clients retry the
Kubernetes token on the next request after the cache entry expires. Go clients extend the expiry after each successful
legacy request, so continued traffic can keep them on the legacy token. Restart the caller pods to retry immediately.

## DBaaS and MaaS

In DBaaS and MaaS, `M2M_AUTH_MODE` sets which credentials they accept. `hybrid` and `k8s` behave the same:

| Mode | Accepted credentials |
| --- | --- |
| `legacy` (default) | Basic only; any Bearer token gets `401` |
| `hybrid`, `k8s` | Basic and the Kubernetes token with the `dbaas` or `maas` audience |

Calls through dbaas-agent and maas-agent continue to work in every mode because the agents send Basic credentials.

In `hybrid` and `k8s`, DBaaS and MaaS fetch the cluster's OIDC discovery document and JWKS at startup and do not start
if that fails. Their pods must have the default service account token mounted.

### dbaas-operator

In dbaas-operator, `M2M_AUTH_MODE` sets how it authenticates to DBaaS. In `legacy` it sends Basic credentials. In
`hybrid` and `k8s` it sends only the Kubernetes token with the `dbaas` audience, without a fallback to Basic, so it
needs DBaaS in `hybrid` or `k8s`. The operator in `legacy` works with DBaaS in any mode.

## Moving an installation to `k8s`

The Cloud Core charts take `M2M_AUTH_MODE`; set the same value for all Cloud Core services. A caller can only be
switched to `k8s` once every service it calls accepts the Kubernetes token, so move receivers first:

1. Upgrade Cloud Core and your services to release 26.4. M2M calls stay in `legacy` mode.
2. Switch DBaaS and MaaS to `hybrid` or `k8s`.
3. Prepare Cloud Core and your services as described below, and switch them to `hybrid`. Find the calls that still
   fall back, as described in [Troubleshooting](#troubleshooting).
4. Switch Cloud Core and your services to `k8s`. Services on the Cloud Core libraries then no longer use dbaas-agent and
   maas-agent.

Consul login follows `M2M_AUTH_MODE` too, unless its own setting, `CONSUL_AUTH_MODE`, is set. Steps 3–4 therefore
move the Consul login of a service without `CONSUL_AUTH_MODE` as well, and that needs the Consul auth method and its
binding rules in place first. Set `CONSUL_AUTH_MODE=legacy` to keep the Consul login where it is while the M2M calls
move; see [Consul login with the Kubernetes projected token](consul-k8s-login-release-notes.md).

## What to change in your service

### Add the mode and the tokens to the chart

Add the parameter with the default `legacy` to `values.yaml`, and restrict it in `values.schema.json`:

```yaml
M2M_AUTH_MODE: legacy
```

```json
"M2M_AUTH_MODE": {
  "type": "string",
  "description": "Machine-to-machine authentication mode: legacy, hybrid, or k8s",
  "enum": ["legacy", "hybrid", "k8s"]
}
```

Give the pod a service account of its own, because receivers identify the caller by it. Project a token for each
audience the service calls, and mount the volume in every mode, as the Cloud Core charts do:

```yaml
spec:
  serviceAccountName: '{{ .Values.SERVICE_NAME }}'
  volumes:
    - name: projected-tokens
      projected:
        defaultMode: 292
        sources:
          - serviceAccountToken:
              path: netcracker/token
              audience: netcracker
              expirationSeconds: 600
          - serviceAccountToken:
              path: dbaas/token
              audience: dbaas
              expirationSeconds: 600
          - serviceAccountToken:
              path: maas/token
              audience: maas
              expirationSeconds: 600
  containers:
    - name: '{{ .Values.SERVICE_NAME }}'
      volumeMounts:
        - name: projected-tokens
          mountPath: /var/run/secrets/tokens
          readOnly: true
      env:
        - name: M2M_AUTH_MODE
          value: '{{ .Values.M2M_AUTH_MODE }}'
        - name: API_DBAAS_ADDRESS
          value: '{{ .Values.API_DBAAS_ADDRESS }}'
        - name: MAAS_INTERNAL_ADDRESS
          value: '{{ .Values.MAAS_INTERNAL_ADDRESS }}'
```

### Set the DBaaS and MaaS addresses

| Environment variable | Property | Value |
| --- | --- | --- |
| `API_DBAAS_ADDRESS` | `api.dbaas.address` | DBaaS URL, such as `http://dbaas-aggregator.<dbaas-namespace>:8080` |
| `MAAS_INTERNAL_ADDRESS` | `maas.internal.address` | MaaS URL, such as `http://maas-service.<maas-namespace>:8080` |

The addresses have no default. An empty value counts as not set, so the parameters can stay empty in `legacy`.

- In `k8s`, creating a DBaaS or MaaS client without its address fails, so the service fails before its first call.
- In `hybrid`, a client without the address uses the agent and logs a warning.

### Make your own M2M calls through the libraries

If the service calls other services with an HTTP client of its own, build that client with the library, so that it
sends the token of the mode.

In Java, `M2MClient` from `com.netcracker.cloud.security.core.utils:k8s-utils` builds an OkHttp client that sets the
token. Its default audience is `netcracker`. In `legacy` and `hybrid` it needs a supplier of the legacy token, and
`build()` throws `IllegalStateException` without one. On Spring:

```java
import com.netcracker.cloud.restclient.MicroserviceRestClient;
import com.netcracker.cloud.restclient.okhttp.MicroserviceOkHttpRestClient;
import com.netcracker.cloud.security.core.auth.M2MManager;
import com.netcracker.cloud.security.core.utils.k8s.M2MClient;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;

@Bean
public MicroserviceRestClient ordersRestClient(M2MManager m2mManager) {
    OkHttpClient client = M2MClient.builder()
            .keycloakTokenSupplier(() -> m2mManager.getToken().getTokenValue())
            .build();
    return new MicroserviceOkHttpRestClient(client);
}
```

On Quarkus, pass `() -> M2MManager.getInstance().getToken().getTokenValue()` with
`com.netcracker.cloud.quarkus.security.auth.M2MManager`.

In Go, `rest.NewM2MRestClient()` returns a client for the `netcracker` audience. In `legacy` and `hybrid` the
constructor loads the service's `security.TokenProvider` and panics if none is registered, so create the client after
the provider is registered:

```go
package orders

import (
	"context"
	"net/http"

	"github.com/netcracker/qubership-core-lib-go/v3/security/rest"
)

type Client struct {
	m2m *rest.M2MRestClient
}

func NewClient() *Client {
	return &Client{m2m: rest.NewM2MRestClient()}
}

func (c *Client) GetOrders(ctx context.Context) (*http.Response, error) {
	return c.m2m.DoRequest(ctx, http.MethodGet, "http://orders:8080/api/v1/orders", nil, nil)
}
```

For fasthttp, websocket, and other clients that cannot go through `DoRequest`, use `rest.NewM2MRequestSender()` from
the same package. Its `Send(ctx, url, send)` calls `send(token)` with the token of the mode; `send` makes the request
and returns the status code, which `Send` uses to decide on the fallback.

The API is described in the
[Java Kubernetes utilities](https://github.com/Netcracker/qubership-core-java-libs/tree/main/core-utils/k8s) and the
[Go REST client](https://github.com/Netcracker/qubership-core-lib-go/tree/main/security/rest).

## Troubleshooting

| Message | Cause | Action |
| --- | --- | --- |
| `M2M_AUTH_MODE has unsupported value "<value>": set it to legacy, hybrid, or k8s` | The variable holds something other than the three modes | Set `legacy`, `hybrid`, or `k8s` |
| `api.dbaas.address is not set: with M2M_AUTH_MODE=k8s the client sends requests directly to DBaaS, ...` | `k8s` without the DBaaS address | Set `API_DBAAS_ADDRESS` |
| `maas.internal.address is not set: with M2M_AUTH_MODE=k8s the client sends requests directly to MaaS, ...` | `k8s` without the MaaS address | Set `MAAS_INTERNAL_ADDRESS` |
| `DBaaS address is not available, falling back to dbaas-agent. ...`, `MaaS address is not available, falling back to maas-agent. ...` | `hybrid` without the address; the client uses the agent | Set the address before switching to `k8s` |
| Warning with `error acquiring kubernetes token for m2m communication` or `cannot get the Kubernetes M2M token for <url>` | `hybrid` and no token for the audience; the client falls back to the legacy token | Mount the projected token of that audience |
| MaaS logs `bearer tokens are not accepted with M2M_AUTH_MODE=legacy, ...` | MaaS runs in `legacy` | Switch MaaS to `hybrid` or `k8s` |
| `401` from DBaaS or MaaS in `k8s` | The receiver rejects the token | Check the receiver's mode and logs for audience, issuer, expiry, or signature errors |

The fallback after a `401` is logged only at `DEBUG` level. To find the receivers that still answer `401` to the
Kubernetes token in `hybrid`, set the `DEBUG` level for the `rest-client` logger in Go
(`logging.level.rest-client=debug`) or for `com.netcracker.cloud.security.core.utils.k8s.impl.M2MInterceptor` in Java.
Restart the caller pods to clear cached fallback decisions before checking.
