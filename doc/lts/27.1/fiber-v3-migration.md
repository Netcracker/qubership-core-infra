# Fiber v3: fiber-server-utils v3 and the cloud core services

The fiber-server-utils library (`github.com/netcracker/qubership-core-lib-go-fiber-server-utils`) builds the HTTP
server of a service on the [Fiber](https://gofiber.io) framework: it adds the health and metrics endpoints, tracing,
access checks, and error handling. fiber-server-utils v3 is built on Fiber v3 instead of Fiber v2, and the cloud core
services already use it.

- **Part 1** is for developers of services on fiber-server-utils: how to move to v3.
- **Part 2** is for those developers and for anyone who calls the APIs of the cloud core services: what behavior
  changes.

## Part 1. Moving to fiber-server-utils v3

fiber-server-utils v3 requires Go 1.26.5 or later and Fiber v3.7.0. The examples below import the packages under these
names:

| Name in the examples | Import | What the examples use from it |
| --- | --- | --- |
| `fiber` | `github.com/gofiber/fiber/v3` | Fiber: the application `fiber.App`, the handler context `fiber.Ctx`, the settings `fiber.Config` and `fiber.ListenConfig` |
| `fiberserver` | `github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v3` | `fiberserver.New` creates a Fiber application with the standard middleware and endpoints |
| `server` | `github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v3/server` | `server.StartServer` starts the application on the address from the configuration |
| `fibererrors` | `github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v3/errors` | `fibererrors.DefaultErrorHandler` answers handler errors in the TMF format |
| `security` | `github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v3/security` | `security.SecurityMiddleware` is the interface of the access-check middleware |
| `logger` | `github.com/netcracker/qubership-core-lib-go/v3/logging` | A logger of the service: `logger.InfoC(ctx, ...)` writes a line with the request data from `ctx` |
| `context`, `json`, `time` | Go standard library: `context`, `encoding/json`, `time` | `context.Context`, `json.Unmarshal`, `time.Second` |

The other changes in Fiber itself, not related to fiber-server-utils, are described in the
[Fiber v3 migration guide](https://docs.gofiber.io/whats_new).

### Step 1. Update the dependencies

Replace the module paths in `go.mod` and in every import:

| v2 | v3 |
| --- | --- |
| `github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2` | `github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v3` |
| `github.com/gofiber/fiber/v2` | `github.com/gofiber/fiber/v3` |
| `github.com/gofiber/adaptor/v2` | `github.com/gofiber/fiber/v3/middleware/adaptor` |

After `go mod tidy`, check which old versions are left among the dependencies, transitive ones included. The command
lists them; a module in its output can come from a dependency that has not moved to Fiber v3 yet:

```bash
go list -m all | grep -E 'gofiber/fiber/v2|gofiber/adaptor/v2|fiber-server-utils/v2'
```

### Step 2. Replace `*fiber.Ctx` with `fiber.Ctx`

In Fiber v2, a function that handles a request received the pointer `*fiber.Ctx`. In Fiber v3, `fiber.Ctx` is an
interface, and the function receives it without the asterisk. Replace it wherever `*fiber.Ctx` appears: in route
handlers, in middleware, in your implementation of `security.SecurityMiddleware`, and in the `Handle` method of your
errors that `fibererrors.DefaultErrorHandler` calls.

```go
// v2
app.Get("/api/v1/items", func(c *fiber.Ctx) error { ... })

// v3
app.Get("/api/v1/items", func(c fiber.Ctx) error { ... })
```

### Step 3. Get the request context with `c.Context()`

For every request, fiber-server-utils creates a `context.Context` with the request data: `request_id`, `tenant_id`, and
other values from the headers. A handler passes this context to the logger and to calls of other services, so that the
request data reaches the logs and the outgoing requests. Fiber v3 renames the methods that work with it:

| Fiber v2 | Fiber v3 | What it does |
| --- | --- | --- |
| `c.UserContext()` | `c.Context()` | Returns the request `context.Context` |
| `c.SetUserContext(ctx)` | `c.SetContext(ctx)` | Replaces the request `context.Context` |
| `c.Context()` | `c.RequestCtx()` | Returns the request object of fasthttp, the library Fiber is built on (`*fasthttp.RequestCtx`). Rarely needed, for example to upgrade to WebSocket |

```go
// v2
func getItems(c *fiber.Ctx) error {
    ctx := c.UserContext()
    logger.InfoC(ctx, "get items")
    items, err := itemService.GetItems(ctx)
    ...
}

// v3
func getItems(c fiber.Ctx) error {
    ctx := c.Context()
    logger.InfoC(ctx, "get items")
    items, err := itemService.GetItems(ctx)
    ...
}
```

Do not pass `c` itself instead of the context. In Fiber v3, `fiber.Ctx` implements `context.Context` too, so such code
compiles and runs. But `c` carries no request data: the logs lose `request_id`, and the outgoing requests carry neither
`request_id` nor `tenant_id`. Neither the compiler nor `go vet` finds such places.

```go
items, err := itemService.GetItems(c)           // compiles, but the request data is lost
items, err := itemService.GetItems(c.Context()) // correct
```

### Step 4. Move the network setting to the server start

This step is needed if the service must accept IPv6 connections. On v2, such services set `Network: fiber.NetworkTCP`
in the application settings passed to `fiberserver.New`. `fiber.Config` in Fiber v3 has no such field: the listener
settings moved to `fiber.ListenConfig`, which is passed when the server starts. That is why `server.StartServer` now
takes an optional third argument, `fiber.ListenConfig`.

```go
// v2
app, err := fiberserver.New(fiber.Config{Network: fiber.NetworkTCP, IdleTimeout: 30 * time.Second}).
    WithPrometheus("/prometheus").
    Process()
...
server.StartServer(app, "http.server.bind")

// v3
app, err := fiberserver.New(fiber.Config{IdleTimeout: 30 * time.Second}).
    WithPrometheus("/prometheus").
    Process()
...
server.StartServer(app, "http.server.bind", fiber.ListenConfig{ListenerNetwork: fiber.NetworkTCP})
```

- `fiber.NetworkTCP` means IPv4 and IPv6. Without `fiber.ListenConfig`, the server listens on IPv4 only, as on v2
  without `Network`. `server.StartServerOnAddress` takes the same argument.
- If you only delete `Network` from `fiber.Config` to make the code compile, the service stops accepting IPv6.

### Step 5. Keep the `Content-Type` header of JSON responses

`c.JSON(body)` writes `body` to the response body as JSON and sets the `Content-Type` response header. Fiber v3 sets a
different value of this header:

```go
return c.JSON(items)
// Fiber v2: Content-Type: application/json
// Fiber v3: Content-Type: application/json; charset=utf-8

return c.JSON(items, fiber.MIMEApplicationJSON)
// Fiber v3: Content-Type: application/json, as on v2
```

The responses of fiber-server-utils itself, such as the `/health` endpoint and `fibererrors.DefaultErrorHandler`, keep
`application/json`. To keep the responses of your service unchanged too, add `fiber.MIMEApplicationJSON` to every
`c.JSON` call, including the `Handle` methods of your errors.

### Step 6. Check how the request body is parsed

Fiber v3 replaces `c.BodyParser` with `c.Bind().Body`:

```go
// v2
if err := c.BodyParser(&tenant); err != nil { ... }

// v3
if err := c.Bind().Body(&tenant); err != nil { ... }
```

The new method is stricter about the `Content-Type` header of an incoming request. On v2, `BodyParser` parsed a body as
JSON whenever its `Content-Type` ended in `json`. On v3, `Bind().Body` parses only `application/json` and
`application/<type>+json` as JSON and returns the error `Unprocessable Entity` for other types, such as `text/json` or
`application/x-json`.

The error text for malformed JSON changes too: `bind from body: ` precedes the parser message. On v2 it is
`unexpected end of JSON input`, on v3 `bind from body: unexpected end of JSON input`.

What to consider:

- If the clients of the service may send `text/json`, warn them to move to `application/json`, or parse the body
  yourself: `json.Unmarshal(c.Body(), &tenant)`.
- If the service returns the parse error text to the client, warn the clients that it changes.

### Step 7. Check the code that uses the request path

The Fiber v3 router resolves `..` and decodes `%`-encoded letters, digits, and `-._~` in the path before it selects a
route (item 4 in part 2), and `c.Path()` returns
the processed path: for the request `/api/v1/a/../items`, it returns `/api/v1/items`. Check the code if the service
passes `c.Path()` on, for example proxies the request to another service.

## Part 2. Behavior changes

Items 1–6 apply to any service on fiber-server-utils v3, including the cloud core services: control-plane,
dbaas-agent, maas-agent, paas-mediation, site-management, facade-operator, core-legacy-api, and rate-limiter. Item 1
does not apply to rate-limiter, which answers a request to an unknown path itself.

1. **A request to a path that has no route**
   - v2: `404`, error text `Cannot GET /<path>`.
   - v3: `404`, error text `Not Found`.
2. **A request with a non-standard HTTP method, such as `FOO`**
   - v2: `400`, body `Invalid http method`.
   - v3: `501`, body `Not Implemented`.
   - `501` is a server error status, so the `5xx` metrics of a mesh sidecar and of an ingress count such requests, for
     example during a method scan.
3. **A request with the `QUERY` method**
   - v2: `400`, body `Invalid http method`.
   - v3: handled like any other method.
4. **A path with `..` segments or with `%`-encoded letters, digits, or `-._~`**, such as `GET /api/v1/a/../items` or
   `GET /api/v1/items/%61bc`
   - v2: the path is used as sent. `/api/v1/a/../items` matches no route and gets `404`.
   - v3: the path is shortened and decoded first: the requests are handled as `GET /api/v1/items` and
     `GET /api/v1/items/abc`. dbaas-agent and maas-agent pass the processed path on to dbaas-aggregator and MaaS.
5. **A request that the server refuses before it selects a route**

   A body over the limit (`413`), headers that are too large (`431`), or a malformed `Content-Length` header (`400`).
   The response to the client is the same on v2 and v3.

   - v2: the error log line has `request_id=-`.
   - v3: the error log line has the `request_id`. Tracing and metrics do not count such a request, as on v2.
   - Fiber v3 runs the middleware registered with `app.Use` for such requests too. If a middleware of the service
     returns an error for such a request, Fiber writes the line `serverErrorHandler: middleware traversal failed` to
     its log.
6. **A request with a query string to a disabled deprecated API**

   fiber-server-utils disables deprecated endpoints if the application is created with
   `WithDeprecatedApiSwitchedOff()` and the configuration sets `deprecated.api.disabled: true`; in cloud core,
   dbaas-agent and paas-mediation support it. Requests to the paths in `deprecated.api.patterns` get `404` with the
   error code `NC-COMMON-2101`. For example, the list has the pattern `/api/v1/items [GET]`.

   - v2: `GET /api/v1/items` is declined, but `GET /api/v1/items?limit=10` is served: a query string bypassed the
     pattern.
   - v3: both requests are declined.
   - Patterns that end with `/**`, such as `/api/v1/**`, decline requests with a query string on both versions.

### Changes in individual cloud core services

1. **A request body whose `Content-Type` ends in `json` but is neither `application/json` nor
   `application/<type>+json`**, such as `text/json`, in dbaas-agent, site-management, core-legacy-api, and
   paas-mediation
   - v2: the body is parsed as JSON, and the request is executed.
   - v3: the request is declined and changes nothing: `400`, `500` in dbaas-agent.
   - Bodies with `application/json` and `application/<type>+json` are accepted the same way on v2 and v3.
2. **The paas-mediation response to malformed JSON in a request that creates or updates a config map, route, or
   service**
   - v2: `400`, error text `failed to parse request body: unexpected end of JSON input`.
   - v3: `400`, error text `failed to parse request body: bind from body: unexpected end of JSON input`.
3. **The dbaas-agent response to a delete by classifier with a body that cannot be parsed**, for example with
   `Content-Type: application/xml` and a body that is not XML
   - v2: `500`, `{"error":"failed to unmarshal: EOF"}`.
   - v3: `500`, `{"error":"bind from body: failed to unmarshal xml: EOF"}`.
4. **The rate-limiter log for requests refused before a route is selected**
   - v2: Fiber writes nothing.
   - v3: the line `[Error] serverErrorHandler: middleware traversal failed: ...` may appear in stderr. The response to
     the client is the same on v2 and v3. Alerts on log lines of level `Error`, if any, may fire.
