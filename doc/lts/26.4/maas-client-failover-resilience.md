# MaaS client libraries — failover and switchover

A rolling node replacement moves a storage leader several times. These changes make the MaaS
client libraries survive that, and add the tests that demonstrate it.

Tracked by [qubership-core-infra#340](https://github.com/Netcracker/qubership-core-infra/issues/340).

## Behavior changes

Six things change for code that is working today, and each says what to do about it. Everything
below this section is an addition or a fix that asks nothing of a caller.

### How long a failing call takes has changed, in both directions

**Applies to:** qubership-core-lib-go-maas-client, qubership-core-java-libs/maas-client

The Java client used to give up on the first unexpected status, `5xx` and `405` included; it
repeated an `IOException` but nothing else. The Go Rabbit client did not retry at all. Both now
repeat a call within a bounded duration, 60s by default, so a call that used to fail in a second
can take a minute.

The Go Kafka client moves the other way. It used to retry every response, `4xx` included, 30 times
a second apart, and the default resty client added retries of its own on top of that. Now `5xx` and
`429` are retried, `405` only when the error reason names a database that cannot be written, and
every other `4xx` fails on the first attempt.

**What to do:** check that any deadline your service sets around a MaaS call still fits, and move
the bound where it does not. In Java it is a property, and `0` disables retrying outright:

```properties
maas.http.retry.max-total-duration-ms=15000
```

In Go it is an option on `kafka.NewClient` and `rabbit.NewClient`:

```go
client := kafka.NewClient(namespace, maasAgentUrl, tenantManagerUrl, httpClient, dialer, authSupplier,
    util.WithMaxTotalDuration(15*time.Second))
```

Go has no off switch: a zero or negative duration means the default 60s, not "do not retry".

The declarative Kafka client is not affected: it keeps its own loop, which repeats every five
seconds until the topic answers, and it now sends one attempt per call, exactly as before.

### Go: a failed call reports a different message

**Applies to:** qubership-core-lib-go-maas-client

A call that ends on a response the client cannot use now reports
`maas-agent responded with status: <status>, body: <body>`. It used to report
`response with error code reveived. Status: ..., body: ...`, typo included. Code that matches on
that text stops matching.

**What to do:** read the status from the error instead of the message:

```go
var httpErr *util.HttpError
if errors.As(err, &httpErr) && httpErr.StatusCode == http.StatusBadRequest {
    // maas-service rejected the request; repeating it will not help
}
```

`GetTopic` and `GetVhost` are the exception: they report a missing registration as a nil result
with no error, so there is no `404` to match on.

### Java: deletes are no longer retried

**Applies to:** qubership-core-java-libs/maas-client

`deleteTopic` and `deleteTopicTemplate` are not idempotent: a repeat of a delete whose response was
lost reports zero deleted topics, or `404` for a template, so the retry turned a completed delete
into a reported failure. The Go client keeps retrying `DeleteTopic`, which reports nothing but an
error and answers a repeat the same way it answered the first attempt.

**What to do:** if you need a delete to survive a switchover, repeat it yourself and read the
answer as "already gone" rather than as a failure. `deleteTopic` returns `false` when it deleted
nothing, so a repeat is safe to run to the end:

```java
MaaSHttpException lastFailure = null;
for (int attempt = 0; attempt < 3; attempt++) {
    try {
        // false means nothing was deleted, which is what a repeat of a completed delete reports
        kafkaClient.deleteTopic(classifier);
        return;
    } catch (MaaSHttpException e) {
        lastFailure = e;
        TimeUnit.SECONDS.sleep(1);
    }
}
throw lastFailure;
```

The example is for topics. A template that is already gone answers `404`, and `MaaSHttpException`
carries no status, so the loop above cannot tell that answer from a real failure and would keep
repeating a delete that has already succeeded. Treat the first failure of `deleteTopicTemplate` as
final.

### Java: the watch poll window follows `maas.http.timeout`

**Applies to:** qubership-core-java-libs/maas-client

The window used to be fixed at 60s, which outlasted the read timeout and made every quiet poll fail
locally. It now derives from `maas.http.timeout` — 25s with the default 30s timeout. Below a
two-second timeout the watch stops working, because the window no longer leaves room for a poll.

**What to do:** if you have set `maas.http.timeout` below 2 (it is in seconds), raise it:

```properties
maas.http.timeout=30
```

### Java: `watchTopicCreate` now throws instead of registering a dead callback

**Applies to:** qubership-core-java-libs/maas-client

`KafkaMaaSClient.watchTopicCreate` throws `IllegalStateException` after `close()`, and after the
watch thread has stopped on its own. It used to accept the callback and never call it.

**What to do:** register watches before closing the client, and let the exception surface rather
than catching it — a client whose watch thread has stopped cannot be revived, so the remedy is to
build a new one.

### A new runtime dependency carries the retry policies

**Applies to:** qubership-core-lib-go-maas-client (`failsafe-go`),
qubership-core-java-libs/maas-client (`dev.failsafe:failsafe`)

**What to do:** nothing, unless your build enforces dependency convergence or pins versions in a
BOM — then add it there. `dev.failsafe:failsafe` has no transitive dependencies of its own:

```xml
<dependency>
    <groupId>dev.failsafe</groupId>
    <artifactId>failsafe</artifactId>
    <version>3.3.2</version>
</dependency>
```

In Go it arrives through `go.mod` as `github.com/failsafe-go/failsafe-go v0.9.7`, and brings one
indirect dependency, `github.com/bits-and-blooms/bitset`. Run `go mod tidy` after the upgrade.

## Additions and fixes, by component

Several of these fixes make calls work that never worked before.

### qubership-core-lib-go-maas-client

- **Changed:** which responses are retried — `5xx`, `429`, and `405` when the error reason names a
  database that cannot be written, which is how maas-service reports a demoted Patroni node. Every
  response used to be retried, `4xx` included; `401` in particular is not, because the token
  provider refreshes on its own schedule.
- **Changed:** a call is bounded by a total duration (60s) rather than by an attempt count, with
  exponential backoff and ±20% jitter. The pause used to be a flat second.
- **Added:** `util.WithMaxTotalDuration` and `util.WithAttemptTimeout`; neither bound was reachable
  from a service before.
- **Added:** a per-attempt timeout of 30s, taken from what is left of the call deadline. Neither
  level had one, so a hung agent could hold a call indefinitely.
- **Added:** `util.HttpError` and `util.RetriesExhaustedError`, so a caller can tell a permanent
  `400` from a call that was repeated until its time ran out.
- **Added:** Rabbit CRUD calls are retried; only Kafka calls were before.
- **Fixed:** `GetOrCreateVhost` posted a bare classifier where maas-service expects a wrapped one,
  so it never succeeded.
- **Fixed:** a down agent was re-polled by the topic watch as fast as the socket could refuse the
  connection. The poll now backs off exponentially with jitter, and its long-poll window derives
  from the HTTP client timeout instead of outlasting it.
- **Fixed:** five tenant-watch defects, including a broadcaster left dead after a round that gave
  up, and a stalled watcher wedging it.
- **Fixed:** a call that ran out of its own duration reported the deadline instead of the failure
  that kept repeating, losing the reason exactly where it is needed.

### qubership-core-lib-go-maas-core

- **Fixed:** the default resty client carried retries of its own, which multiplied the attempts the
  maas client makes. `WithHttpClient` now documents the two settings a replacement has to keep.

### qubership-core-lib-go-maas-segmentio

- **Added:** `WriterOptions.RequiredAcks`, so the acknowledgement trade-off is chosen where the
  writer is built. The default is unchanged — kafka-go's `RequireNone`, which reports success for
  messages a leader change drops. The README now states what each level costs.
- **Fixed:** `AlterTransport` and `AlterDialer` passed a hook's nil result on as a nil field, and
  the failure surfaced later at the first write.
- **Tests:** failover coverage against a three-broker cluster — a producer losing its partition
  leader, a rolling broker restart, and a reader losing its group coordinator.

### qubership-core-lib-go-maas-bg-segmentio

- **Fixed:** `ReadMessage` returned a wrapper around the zero value alongside the error, which the
  bg-kafka consumer loop driving the adapter could process as a record. It now returns no message.
- **Tests:** failover coverage for the blue-green consumer losing its coordinator.

### qubership-core-java-libs / maas-client

- **Changed:** the same retry contract as the Go client — response-based, bounded by
  `maas.http.retry.max-total-duration-ms` (60s), `405` only on a read-only database, and no `401`.
  Deletes are the one difference: Java does not retry them, see above.
- **Added:** `MaaSHttpException`, so a failed call names the failure instead of throwing a bare
  `RuntimeException`. It is deliberately not a `MaaSException` — that one means maas-service
  refused the request, this one means it never answered — so an existing `catch (MaaSException)`
  keeps its meaning and does not start swallowing outages.
- **Fixed:** the watch long poll ran without pacing, so a down agent was re-polled as fast as the
  socket could refuse the connection. It is now backed off exponentially with jitter.
- **Fixed:** watch thread lifecycle — parking shared the thread object with `join()` in `close()`,
  and an interrupt during a retry wait was swallowed.
- **Fixed:** an empty `200` body threw in `deleteTopic` and `search`.

### qubership-core-java-libs / maas-kafka-client

- **Fixed:**
  a producer whose Kafka client refused to close was left reporting `ACTIVE` with a closed
  producer — every later write failed and the activation event could not revive it, because
  activation only accepts `INITIALIZED` and `INACTIVE`. Closing is best effort now; the state
  transition is not.

### maas-service

- **Fixed:** the topic template query now runs on the read-only cache. It used to fail there — it
  reads a PostgreSQL array column, and the cache is SQLite — and the cache's own failure replaced
  the availability error that caused the fallback, so a leader change came back as
  `400 MAAS-0600 input error`, which no client can retry and none should. The array predicates are
  dialect-aware now, and a cache that cannot serve a request reports the outage as `503`.

## Tests

The scenario in the ticket is a rolling node replacement, so the tests inject exactly that rather
than asserting on code paths.

- `qubership-core-test-apps` gains the **maas-failover-resilience** suite: two applications
  (Spring, Go) driven from inside the cluster while a fault is injected through the Kubernetes API,
  covering get-or-create topic, get-or-create vhost and the watch subscription, with and without
  maas-agent losing an instance.
- Each scenario asserts that a success occurred within the recovery allowance, that failures
  stopped once the client settled, that no operation hung, and that threads and descriptors
  returned to baseline. Zero-error failover is deliberately not the contract: a leader change
  produces errors, and what is asserted is that they are bounded and recoverable.
- Library-level failover tests run against real multi-broker Kafka clusters in both stacks.
  Recovery times are logged rather than asserted, because they track the cluster's own failure
  detection more than the library.
