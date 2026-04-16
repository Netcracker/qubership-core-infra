# Quarkus 3.27 → 3.33 Migration Guide

Quarkus 3.33 is an LTS release. It does not introduce breaking changes on its own — all changes accumulated in
intermediate versions 3.28–3.32.

This guide is intended for **Quarkus extension libraries** (projects consisting of `runtime` + `deployment` submodules).
For Quarkus applications `quarkus update` is the right tool; for extension libraries it is not — changes must be made
manually.

---

## 1. Update Quarkus version

Find the Quarkus version property in the root `pom.xml` files of your modules and update it to `3.33.1`:

```bash
grep -rn "quarkus.*version.*3\.27" --include="pom.xml"
```

---

## 2. Rename JUnit artifacts (from 3.31)

Quarkus 3.31 migrated from JUnit 5 to JUnit 6. Artifacts were renamed:

| Old `artifactId`           | New `artifactId`          |
|----------------------------|---------------------------|
| `quarkus-junit5`           | `quarkus-junit`           |
| `quarkus-junit5-internal`  | `quarkus-junit-internal`  |
| `quarkus-junit5-mockito`   | `quarkus-junit-mockito`   |
| `quarkus-junit5-component` | `quarkus-junit-component` |
| `quarkus-junit5-config`    | `quarkus-junit-config`    |

Find all occurrences:

```bash
grep -rn "quarkus-junit5" --include="pom.xml"
```

After renaming, run test compilation. If tests fail to compile due to changed JUnit imports — apply the OpenRewrite
recipe mentioned in migration guide 3.31.

---

## 3. Add `@{argLine}` to Surefire (from 3.31)

Migration guide 3.31 requires `<argLine>@{argLine}</argLine>` in the Surefire plugin configuration.

---

## 4. Update Testcontainers to 2.x (from 3.31)

Quarkus 3.31 moved to Testcontainers 2.0. The version in Quarkus 3.33.1 BOM: **2.0.3**.

### 4.1 Update the version

Find any explicitly declared `<testcontainers.version>` and remove them (the version should come from the Quarkus BOM).

### 4.2 Rename dependency artifactIds

In Testcontainers 2.x all modules received the `testcontainers-` prefix:

| Old `artifactId` | New `artifactId`               |
|------------------|--------------------------------|
| `junit-jupiter`  | `testcontainers-junit-jupiter` |
| `consul`         | `testcontainers-consul`        |
| `kafka`          | `testcontainers-kafka`         |
| `cassandra`      | `testcontainers-cassandra`     |
| `mongodb`        | `testcontainers-mongodb`       |
| `postgresql`     | `testcontainers-postgresql`    |
| `rabbitmq`       | `testcontainers-rabbitmq`      |

```bash
grep -rn "groupId>org.testcontainers" --include="pom.xml" -A1
```

### 4.3 Check Java test code

Container classes moved to module-specific packages. Old names (`org.testcontainers.containers.*`) are kept as
backward-compatible aliases in 2.0.3 so compilation will not break, but updating imports is recommended:

| Old import                           | New import                           |
|--------------------------------------|--------------------------------------|
| `o.t.containers.PostgreSQLContainer` | `o.t.postgresql.PostgreSQLContainer` |
| `o.t.containers.MongoDBContainer`    | `o.t.mongodb.MongoDBContainer`       |
| `o.t.containers.KafkaContainer`      | `o.t.kafka.KafkaContainer`           |
| `o.t.containers.CassandraContainer`  | `o.t.cassandra.CassandraContainer`   |
| `o.t.containers.RabbitMQContainer`   | `o.t.rabbitmq.RabbitMQContainer`     |

`getContainerIpAddress()` was removed — replace with `getHost()`.

```bash
grep -rn "getContainerIpAddress" --include="*.java"
```

---

## 5. Runtime config in `@BuildStep` (from 3.28–3.33)

Quarkus forbids passing `@ConfigRoot(phase = RUN_TIME)` objects as direct parameters of a `@BuildStep` method.

**Symptom:**

```
java.lang.IllegalArgumentException: Run time configuration cannot be consumed in Build Steps
```

**Fix:** remove the config from `@BuildStep` parameters, register the bean as unremovable, and look it up via
`Arc.container()` inside the recorder method:

```java
// Processor
@BuildStep
UnremovableBeanBuildItem unremovableBeans() {
    return UnremovableBeanBuildItem.beanTypes(MyRuntimeConfig.class);
}

@Record(ExecutionTime.RUNTIME_INIT)
@BuildStep
SomeBuildItem myStep(MyRecorder recorder) {
    recorder.doSomething();
}

// Recorder
public void doSomething() {
    MyRuntimeConfig config = Arc.container().instance(MyRuntimeConfig.class).get();
    // use config
}
```

> **Important:** without `UnremovableBeanBuildItem` Quarkus will remove the config bean during dead bean elimination,
> and `Arc.container().instance(...)` will return `null`.

Check all `@BuildStep` methods in deployment modules:

```bash
grep -rn "RUN_TIME" --include="*.java" **/deployment/
```

---

## 6. New `AgroalDataSource.getReadOnlyConnection()` method (Agroal 3.0.1)

Agroal 3.0.1 (shipped with Quarkus 3.33) added a new abstract method:

```java
Connection getReadOnlyConnection() throws SQLException;
```

Implement it in all classes that implement `AgroalDataSource`, delegating to the inner datasource:

```java

@Override
public Connection getReadOnlyConnection() throws SQLException {
    return innerDataSource.getReadOnlyConnection();
}
```

---

## 7. `MongodbConfig` → `MongoConfig` rename

The class `io.quarkus.mongodb.runtime.MongodbConfig` was renamed to `io.quarkus.mongodb.runtime.MongoConfig`.

```bash
grep -rn "MongodbConfig" --include="*.java"
```

---

## 8. MongoDB driver 5.6.x changes

### 8.1 New abstract method `appendMetadata`

All classes implementing `com.mongodb.client.MongoClient` must implement:

```java

@Override
public void appendMetadata(MongoDriverInformation info) {
    innerClient.appendMetadata(info);
}
```

### 8.2 `MongoClients.createMongoClient/createReactiveMongoClient` became package-private

If your project has a class that extends `io.quarkus.mongodb.runtime.MongoClients` and overrides these methods — remove
the `@Override` annotations (the methods became package-private and cannot be overridden from a different package).

---

## 9. OTel Kafka artifact rename

In Quarkus 3.33 BOM `opentelemetry-kafka-clients-common` was renamed to `opentelemetry-kafka-clients-common-0.11`.

In `pom.xml`:

```xml
<!-- Before: -->
<artifactId>opentelemetry-kafka-clients-common</artifactId>
        <!-- After: -->
<artifactId>opentelemetry-kafka-clients-common-0.11</artifactId>
```

The `opentelemetry-kafka-clients-2.6` artifact is unchanged. Note that `opentelemetry-kafka-clients-2.6` already
transitively depends on `opentelemetry-kafka-clients-common-0.11`, so declaring both explicitly causes no conflict.

### Internal class package rename

If your project uses internal OTel Kafka instrumentation classes:

| Old import                                                                 | New import                                                                                     |
|----------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `io.opentelemetry.instrumentation.kafka.internal.KafkaInstrumenterFactory` | `io.opentelemetry.instrumentation.kafkaclients.common.v0_11.internal.KafkaInstrumenterFactory` |
| `io.opentelemetry.instrumentation.kafka.internal.KafkaProcessRequest`      | `io.opentelemetry.instrumentation.kafkaclients.common.v0_11.internal.KafkaProcessRequest`      |

`KafkaTelemetry` (`io.opentelemetry.instrumentation.kafkaclients.v2_6.KafkaTelemetry`) — package unchanged.

---

## 10. Other configuration checks

### System.getProperty for test port (from 3.31)

`System.getProperty("quarkus.http.test-port")` no longer works. Replace with:

```java
ConfigProvider.getConfig()
    .

unwrap(SmallRyeConfig .class)
    .

getValue("quarkus.http.test-port",Integer .class);
```

```bash
grep -rn "System.getProperty.*quarkus.http\|System.getProperty.*test.url" --include="*.java"
```

### CORS (from 3.28)

`quarkus.http.cors=true` was removed → use `quarkus.http.cors.enabled=true`.

```bash
grep -rn "quarkus.http.cors[^.]" --include="*.properties" --include="*.yml"
```

### JDBC metrics (from 3.30)

`quarkus.datasource.jdbc.enable-metrics` was removed → use `quarkus.datasource.jdbc.metrics.enabled`.

```bash
grep -rn "enable-metrics" --include="*.properties" --include="*.yml"
```

---

## Note for library consumers

These changes do not require updating library code, but are relevant for services that use them:

- **TLS:** Quarkus TLS registry supports only TLS 1.3 by default. To allow TLS 1.2:
  `quarkus.tls.protocols=TLSv1.2,TLSv1.3`.
- **JDBC connection pool:** the default maximum pool size increased from 20 to 50. Set an explicit limit if needed:
  `quarkus.datasource.jdbc.max-size`.
- **Security/transaction ordering:** since 3.31 security interceptors execute before transaction interceptors. Relevant
  when combining `@PermissionsAllowed` + `@Transactional` with a `@PermissionChecker` that accesses the database.

### Affected Qubership Libs

| Library (top-level module)              | Old version     | New version     |
|-----------------------------------------|-----------------|-----------------|
| `core-blue-green-state-monitor-quarkus` | 3.1.0-SNAPSHOT  | 4.0.0-SNAPSHOT  |
| `core-context-propagation-quarkus`      | 8.1.0-SNAPSHOT  | 9.0.0-SNAPSHOT  |
| `core-quarkus-extensions`               | 9.2.0-SNAPSHOT  | 10.0.0-SNAPSHOT |
| `maas-client-quarkus`                   | 10.1.0-SNAPSHOT | 11.0.0-SNAPSHOT |
| `maas-declarative-client-quarkus`       | 8.1.0-SNAPSHOT  | 9.0.0-SNAPSHOT  |

---

## Checklist

- [ ] Quarkus version updated to `3.33.1` in all root pom.xml files
- [ ] All `quarkus-junit5*` renamed to `quarkus-junit*`
- [ ] `@{argLine}` present in Surefire configuration
- [ ] Testcontainers updated to `2.0.3`, dependency artifactIds renamed
- [ ] `getContainerIpAddress()` replaced with `getHost()`
- [ ] No `@BuildStep` method takes a `@ConfigRoot(RUN_TIME)` parameter directly
- [ ] `AgroalDataSource.getReadOnlyConnection()` implemented (if project uses Agroal)
- [ ] `MongodbConfig` → `MongoConfig` (if project uses Quarkus MongoDB)
- [ ] `MongoClient.appendMetadata()` implemented (if project implements `MongoClient`)
- [ ] `opentelemetry-kafka-clients-common` → `opentelemetry-kafka-clients-common-0.11` in pom.xml
- [ ] OTel Kafka internal imports updated to `kafkaclients.common.v0_11.internal`
- [ ] No `System.getProperty("quarkus.http.test-port")` in tests
- [ ] No `quarkus.http.cors=` without `.enabled`
- [ ] No `quarkus.datasource.jdbc.enable-metrics`
- [ ] `mvn verify` passes in all modules

---

## References

- [Migration Guide 3.28](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.28)
- [Migration Guide 3.29](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.29)
- [Migration Guide 3.30](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.30)
- [Migration Guide 3.31](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31)
- [Migration Guide 3.32](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.32)
- [Migration Guide 3.33](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.33)
