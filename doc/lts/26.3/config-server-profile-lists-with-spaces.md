# Config profiles with spaces are now rejected (HTTP 400)

**Applies to:** `qubership-core-config-server`.

This service is built on **Spring Cloud Config Server** and exposes the standard
config endpoint `GET /{application}/{profiles}`, which clients call to fetch a
service's configuration for one or more profiles. The behavior described below
comes from the Spring Cloud Config Server bundled in this service.

## What changed

Upgrading `spring-cloud-dependencies` **2025.1.1 → 2025.1.2** brings
`spring-cloud-config-server` **5.0.1 → 5.0.4**. Since **5.0.3**, its
`EnvironmentController#getEnvironment` validates the requested profile list and
rejects any list that contains whitespace around a comma. Because this validation
ships inside `spring-cloud-config-server`, `qubership-core-config-server` now
returns the error too:

```
GET https://<config-server-host>/{application}/default,%20test
    ->  HTTP 400  ("Invalid Request")
```

The list is split on `,` without trimming, so `default, test` becomes
`["default", " test"]`, and the token `" test"` fails validation.

## Who is affected

Services that call this config-server's REST endpoint **directly**,
bypassing the Spring Cloud Config client, and pass a profiles value that carries a
space (typically a human-authored `default, test` forwarded as-is):

- non-Spring / non-JVM services (Go, Node, Python) fetching config over raw HTTP;
- JVM services calling the endpoint via `WebClient`/`RestTemplate` instead of the
  config client;
- integration tests.

## ⚠️ The spaced form never actually worked

Before the upgrade the same request returned **HTTP 200 but silently dropped the
second profile**: `" test"` never matched the stored profile `test`, so its
properties were never applied. This is **not a broken feature** — the space was
always malformed input. The upgrade only turns a *silent misconfiguration* into
a *visible 400*.

## What to do

Send profiles as a comma-separated list with **no spaces**:

| ❌ Rejected (400) | ✅ Correct   |
|-------------------|--------------|
| `default, test`   | `default,test` |

Check anywhere a profile list is built by hand (direct URLs, scripts, gateways,
tests). Standard Spring Boot config clients already send profiles without spaces
and are unaffected.

This is intended Spring Cloud Config behavior, so the server does not normalize
it away — callers should send well-formed profile lists.
