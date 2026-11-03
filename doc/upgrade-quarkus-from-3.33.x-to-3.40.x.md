# Quarkus 3.33 → 3.40 Migration Guide

Quarkus 3.40 is an LTS release. It does not introduce breaking changes on its own — all changes accumulated in
intermediate versions 3.34–3.39.

This guide is intended for **Quarkus extension libraries** (projects consisting of `runtime` + `deployment` submodules).
For Quarkus applications `quarkus update` is the right tool; for extension libraries it is not — changes must be made
manually.

---

## 1. Update Quarkus version

Find the Quarkus version properties in the `pom.xml` files of your modules and update them to `3.40.1.1`:

```bash
grep -rnE "<quarkus(\.platform)?\.version>" --include="pom.xml"
```

---

## 2. New abstract methods in Agroal 3.2.1

Quarkus 3.40 ships Agroal 3.2.1 (Quarkus 3.33 shipped 3.0.1).

### 2.1 `AgroalConnectionFactoryConfiguration.networkTimeout()`

**Symptom:**

```
AgroalConnectionFactoryConfigurationHolder is not abstract and does not override abstract method networkTimeout()
in io.agroal.api.configuration.AgroalConnectionFactoryConfiguration
```

**Fix:** implement it in all classes that implement `AgroalConnectionFactoryConfiguration`. `Duration.ZERO` is the Agroal
default:

```java
Duration networkTimeout = Duration.ZERO;

@Override
public Duration networkTimeout() {
    return networkTimeout;
}
```

Libraries built against Agroal before 3.2 fail at runtime with `AbstractMethodError: ...networkTimeout()` when a
connection is created. Update the library together with Quarkus (see [Note for library consumers](#note-for-library-consumers)).

### 2.2 `AgroalConnectionPoolConfiguration`

`recoveryEnable()` is no longer a default method. New abstract methods: `establishmentRetryAttempts()`,
`establishmentRetryInterval()`, `interceptors()`. Implement them if your project implements this interface.

```bash
grep -rnE "implements .*AgroalConnection(Factory|Pool)Configuration" --include="*.java"
```

---

## 3. `byte-buddy` excluded from `mockito-core`

**Symptom:** Mockito fails on the first mock:

```
java.lang.IllegalStateException: Could not initialize plugin: interface org.mockito.plugins.MockMaker
...
Caused by: java.lang.NoClassDefFoundError: net/bytebuddy/ClassFileVersion
```

**Fix:** remove any `<exclusion>` of `byte-buddy` from `mockito-core`. The Quarkus 3.40 BOM no longer brings
`byte-buddy` through Hibernate.

```bash
grep -rn "byte-buddy" --include="pom.xml"
mvn dependency:tree -Dincludes=net.bytebuddy:byte-buddy
```

---

## 4. `QuarkusTestProfile.getConfigOverrides()` no longer sets system properties

From Quarkus 3.34, overrides from `getConfigOverrides()` are visible through MicroProfile Config only, not through
`System.getProperty`.

**Symptom:**

```
java.lang.IllegalStateException: Missing required prop: 'cloud.microservice.namespace'
```

```
java.nio.file.NoSuchFileException: /var/run/secrets/kubernetes.io/serviceaccount/token
```

**Fix:**

1. Prefer reading the value through config:

   ```java
   ConfigProvider.getConfig().getValue("my.property", String.class);
   ```

2. Or set the system property in the test:

   ```java
   @BeforeAll
   static void setNamespace() {
       System.setProperty("cloud.microservice.namespace", "test-namespace");
   }

   @AfterAll
   static void clearNamespace() {
       System.clearProperty("cloud.microservice.namespace");
   }
   ```

3. Or pass it to the test JVM (Surefire):

   ```xml
   <configuration>
       <systemPropertyVariables>
           <cloud.microservice.namespace>test-namespace</cloud.microservice.namespace>
       </systemPropertyVariables>
   </configuration>
   ```

```bash
grep -rln "getConfigOverrides" --include="*.java"
grep -rn "System.getProperty" --include="*.java"
```

---

## 5. Test resources and `quarkus.test.flat-class-path=true` (from 3.35)

With `quarkus.test.flat-class-path=true`, values returned from `QuarkusTestResourceLifecycleManager.start()` are dropped.
`QuarkusTestProfile.getConfigOverrides()` is not affected.

**Symptom:**

```
SRCFG00014: The config property quarkus.cassandra.contact-points is required but it could not be found
```

```
Cannot find consul agent url
```

### 5.1 Module does not need a flat class path

Remove from `src/test/resources/application.properties`:

```properties
quarkus.test.flat-class-path=true
```

### 5.2 Module needs a flat class path

Keep the setting and copy the returned map into system properties in the test resource:

```java
public class CassandraContainerResource extends CassandraTestResource {

    private Map<String, String> properties = Map.of();

    @Override
    public Map<String, String> start() {
        properties = super.start();
        properties.forEach(System::setProperty);
        return properties;
    }

    @Override
    public void stop() {
        properties.keySet().forEach(System::clearProperty);
        super.stop();
    }
}
```

Keep a flat class path when the test loads workspace modules and third-party jars that must see the same copy of a class
(for example Cassandra driver metrics id generators). Without it:

```
Expected class ...DbaasTaggingMetricIdGenerator to be a subtype of
com.datastax.oss.driver.internal.core.metrics.MetricIdGenerator
```

```bash
grep -rl "quarkus.test.flat-class-path" --include="*.properties" --include="*.yml" --include="*.yaml" --include="pom.xml" .
grep -rlE "QuarkusTestResourceLifecycleManager|testResources\(\)" --include="*.java" .
```

---

## 6. Align declared versions

Raise declared versions that sit below the Quarkus / Spring Boot BOMs:

| Artifact                                              | Old           | New           |
|-------------------------------------------------------|---------------|---------------|
| `com.google.code.gson:gson`                           | `2.13.2`      | `2.14.0`      |
| `com.google.guava:guava`                              | `33.5.0-jre`  | `33.6.0-jre`  |
| `commons-io:commons-io`                               | `2.21.0`      | `2.22.0`      |
| `io.smallrye:jandex`                                  | `3.5.3`       | `3.6.0`       |
| `org.jetbrains:annotations`                           | `26.0.2`      | `26.1.0`      |
| `io.fabric8:kubernetes-client`                        | `7.5.2`       | `7.8.0`       |
| `org.apache.maven:maven-plugin-api`                   | `3.9.12`      | `3.9.16`      |
| `org.jboss.logmanager:jboss-logmanager`               | `3.2.1.Final` | `3.2.2.Final` |
| `org.springframework.boot:spring-boot-starter-parent` | `4.1.1`       | `4.1.2`       |

```bash
grep -rnE "(2\.13\.2|33\.5\.0-jre|2\.21\.0|26\.0\.2|7\.5\.2|3\.9\.12|3\.2\.1\.Final)<" --include="pom.xml"
grep -rn -A1 "<artifactId>jandex</artifactId>" --include="pom.xml"
```

Remove an explicit `jandex` version so the Quarkus BOM manages it. Leave `jandex-maven-plugin` on `3.5.3` if that is
what the build uses.

Versions left unchanged on purpose: `amqp-client` `5.37.0`, `log4j-*` `2.26.1`, `junit-bom` `6.0.3`.

---

## 7. Changes to review from migration guides 3.34–3.39

### 7.1 REST Assured 6 (from 3.35)

Quarkus 3.40 uses REST Assured 6.0.1. From 3.38 the REST Assured base path includes `quarkus.servlet.context-path` —
remove a manual context path from test URLs if you add it yourself.

```bash
grep -rln "io.restassured" --include="*.java"
grep -rn "servlet.context-path" --include="*.properties" --include="*.yml" --include="*.yaml"
```

### 7.2 Hibernate ORM 7.3 and 7.4 (from 3.35 and 3.37)

Quarkus 3.40.1.1 BOM manages Hibernate ORM `7.4.9.Final`.

| From | Change | Fix | Find |
|------|--------|-----|------|
| 3.35 | `@Columns` removed | replace with the Hibernate 7 equivalent mapping | `grep -rn "annotations.Columns" --include="*.java"` |
| 3.35 | Changing collections of read-only entities is an error | stop mutating those collections | code review |
| 3.35 | `getSingleResult()` / `getSingleResultOrNull()` throw on more than one result | use a query that expects many results, or ensure uniqueness | `grep -rnE "getSingleResult(OrNull)?\(" --include="*.java"` |
| 3.35 | Timeouts throw `PersistenceException` when the transaction is marked for rollback | catch `PersistenceException` (or handle rollback) instead of the old timeout types | `grep -rnE "QueryTimeoutException\|LockTimeoutException" --include="*.java"` |
| 3.35 | `hibernate-core` no longer depends on Classmate | add an explicit Classmate dependency if you still use it | `grep -rn "com.fasterxml.classmate" --include="*.java"` |
| 3.35 | Envers: `@Audited(targetAuditMode = NOT_AUDITED)` is respected | review audit behaviour of relations marked `NOT_AUDITED` | `grep -rn "targetAuditMode" --include="*.java"` |
| 3.35, 3.37 | Schema actions (`import.sql`) run even without `@Entity` | remove unused `import.sql`, or disable schema actions for that module | modules with `import.sql` and no entities |
| 3.37 | Pagination / limit on a query that fetches a collection is applied in SQL | set hint `org.hibernate.limitInMemory` if you need the old in-memory limit | paged queries with collection `fetch` |
| 3.37 | Eager `@Any` associations are join-fetched when loading by id | review load plans / N+1 expectations for `@Any` | `grep -rn "@Any" --include="*.java"` |
| 3.37 | `json_object` / `json_array` encode binary as HEX; `json_value` expects HEX | update readers/writers to HEX | `grep -rnE "json_(object\|array\|value)" --include="*.java"` |
| 3.37 | MySQL: no dialect default for `quarkus.hibernate-orm.fetch.max-depth` | set `quarkus.hibernate-orm.fetch.max-depth=2` if you relied on the old default | MySQL services that relied on implicit `2` |
| 3.37 | Oracle: HQL current/local date → `trunc(current_date)` | review date comparisons / indexes that assumed untruncated values | Oracle services |

Run native queries against a real database; mocked `EntityManager` does not execute them.

### 7.3 Minimum PostgreSQL 14 (from 3.37)

Upgrade PostgreSQL 13 before moving to Quarkus 3.40. An older server fails startup when Quarkus can reach it.

```sql
SHOW server_version;
```

```bash
grep -rnE "postgres(ql)?:[0-9]" --include="*.java" --include="*.yml" --include="*.yaml" --include="*.properties"
```

### 7.4 `@Transactional` no longer forces a worker thread (from 3.35)

Quarkus REST methods with `@Transactional` that return `Uni` / other reactive types run on the event loop. Add
`@Blocking` if the method blocks. Classic RESTEasy (`quarkus-resteasy*`) is not affected.

```bash
grep -rn -A2 "@Transactional" --include="*.java" | grep -E "(Uni|Multi|CompletionStage|CompletableFuture)<"
```

### 7.5 `NOT NULL` on generated columns (from 3.35 and 3.37)

- From 3.35: `@Version` columns are `NOT NULL` by default.
- From 3.37: `@CreationTimestamp` / `@UpdateTimestamp` get `NOT NULL`; `@ElementCollection` set tables get a unique
  constraint where appropriate.

If Hibernate manages the schema, check existing data for `NULL` values. If Flyway manages it, compare migrations with
entities.

```bash
grep -rnE "@Version|@CreationTimestamp|@UpdateTimestamp|@ElementCollection" --include="*.java"
grep -rn "hibernate-orm.database.generation\|hibernate-orm.schema-management" --include="*.properties" --include="*.yml" --include="*.yaml"
```

### 7.6 HTTP `Host` validation (from 3.38)

When `quarkus.http.host` is `localhost`, `127.0.0.1` or `[::1]`, Quarkus validates the `Host` header. Other `Host`
values get `400 Bad Request`.

- set `quarkus.http.host-validation.allowed-hosts`; or
- set `quarkus.http.host-validation.require-localhost=false`; or
- stop the proxy from passing the external `Host`.

```bash
grep -rnE "quarkus\.http\.host\s*=|QUARKUS_HTTP_HOST" --include="*.properties" --include="*.yml" --include="*.yaml"
```

### 7.7 Repeated registration of a Prometheus metric (from 3.34)

**Symptom:**

```
java.lang.IllegalArgumentException: Prometheus requires that all meters with the same name have the same set of tag keys.
```

From 3.34 a second registration with different tag keys throws. Use one set of tag keys per metric name, or different
names. In tests that remove meters between methods, remove every meter of the name (including `<name>.request.total`).

```bash
grep -rnE "MeterRegistry|Metrics\.(counter|timer|gauge)|Counter\.builder|Timer\.builder|Gauge\.builder|@TimeMeasure" --include="*.java"
```

**DBaaS clients** (`DatabaseMetricProperties.getMetricTags()`): tenant vs service databases use different tag key sets.

| Client | Action |
|---|---|
| `dbaas-datasource-postgresql` | Use a library version that logs the registration failure and keeps the database working |
| `dbaas-opensearch-client` | Same |
| `dbaas-cassandra-client` | Same; without the fix turn metrics off with `quarkus.dbaas.cassandra.metrics.enabled=false` |

`maas-kafka-quarkus-client` needs no change (`KafkaClientMetrics` already catches the failure).

### 7.8 Interceptors on default interface methods (from 3.37)

Method-level interceptor bindings on `default` interface methods (for example `@Transactional`) are applied. Remove the
annotation if the method must not be intercepted.

```bash
grep -rnE -A2 "@(Transactional|Retry|Timeout|CircuitBreaker|Counted|Timed|RolesAllowed|PermissionsAllowed)\b" --include="*.java" | grep -E "\bdefault\b"
```

### 7.9 Other changes

| From | Change | Fix | Find |
|------|--------|-----|------|
| 3.35 | Management CORS: `quarkus.management.cors.*` | move CORS settings under `quarkus.management.cors.*` | `grep -rn "quarkus.management" --include="*.properties" --include="*.y*ml"` |
| 3.35 | Kafka Dev Services default provider: `upstream-kafka-native` | set the provider explicitly if you need Redpanda | `grep -rn "kafka.devservices" --include="*.properties" --include="*.y*ml"` |
| 3.36 | TLS SPI: `io.quarkus.tls.runtime` → `io.quarkus.tls` | update imports | `grep -rn "io.quarkus.tls.runtime" --include="*.java"` |
| 3.37–3.39 | Panache Next → Quarkus Data (package rename) | rename dependency / packages per Quarkus guide | `grep -rnE "io.quarkus.hibernate.panache\|quarkus-hibernate-panache-next" .` |
| 3.38 | `LateBoundSpanProcessor` removed | use `SpanProcessorCustomizer` | `grep -rn "LateBoundSpanProcessor" --include="*.java"` |
| 3.38 | `quarkus-opentelemetry` starts a Grafana LGTM Dev Service | disable the Dev Service if you do not want it | `grep -rn "quarkus-opentelemetry" --include="pom.xml"` |
| 3.38 | Elasticsearch low-level client → `Rest5Client` / HTTP Client 5 | migrate client usage | `grep -rn "org.elasticsearch.client" --include="*.java"` |
| 3.38 | `FileUpload#fileName()` is sanitized and can return `null` | handle `null` / sanitized names | `grep -rn "FileUpload" --include="*.java"` |
| 3.38 | `@QuarkusIntegrationTest`: profile overrides win over test resources | adjust test expectations / resource order | `grep -rn "QuarkusIntegrationTest" --include="*.java"` |
| 3.34–3.40 | BOM no longer manages `yasson`, `freemarker`, `elasticsearch-rest-client` | declare an explicit version if you use them | `grep -rnE "<artifactId>(yasson\|freemarker\|elasticsearch-rest-client)" --include="pom.xml"` |

### 7.10 Quarkus Operator SDK

Quarkus 3.40.1.1 brings Quarkus Operator SDK 7.8.0 and Java Operator SDK 5.5.0 (3.33.4.1: 7.7.5 / 5.3.4).

- From JOSDK 5.5, `UpdateControl` no longer filters the event of its own update. Review controllers that disable
  generation-aware processing.
- From JOSDK 5.3, `operator-framework-junit-5` → `operator-framework-junit` (drop its own version).

```bash
grep -rnE "generationAwareEventProcessing|operator-framework-junit-5" --include="*.java" --include="pom.xml"
```

### 7.11 `new PersistentBag(null, ...)` fails on Hibernate ORM 7.4

**Symptom:**

```
java.lang.NullPointerException: Cannot invoke "...getLoadQueryInfluencers()" because "session" is null
	at org.hibernate.collection.spi.PersistentBag.<init>
```

**Fix:** use a plain collection when the object is not attached to a session:

```java
// Before:
copy.setDatabaseRegistry(new PersistentBag(null, registries));
// After:
copy.setDatabaseRegistry(new ArrayList<>(registries));
```

```bash
grep -rnE "new Persistent(Bag|List|Set|Map|SortedSet|SortedMap)(<[^>]*>)?\(null" --include="*.java" .
```

---

## Note for library consumers

These points do not replace the steps above; they matter when a service consumes the libraries:

- Use library versions built for Quarkus 3.40 together with Quarkus 3.40. Libraries built for 3.33 fail at runtime on
  Agroal (`AbstractMethodError: ...networkTimeout()`, [section 2.1](#21-agroalconnectionfactoryconfigurationnetworktimeout)).
- PostgreSQL must be 14+ ([section 7.3](#73-minimum-postgresql-14-from-337)).
- `dbaas-client` includes the Prometheus registration fix ([section 7.7](#77-repeated-registration-of-a-prometheus-metric-from-334)).
- If the service imports `quarkus-operator-sdk-bom` on its own version property, raise it to match Quarkus 3.40.1.1
  ([section 7.10](#710-quarkus-operator-sdk)).

### Affected Qubership Libs

| Library (top-level module)              | Old version     | New version    |
|-----------------------------------------|-----------------|----------------|
| `core-blue-green-state-monitor-quarkus` | 4.2.0-SNAPSHOT  | set at release |
| `core-context-propagation-quarkus`      | 9.3.0-SNAPSHOT  | set at release |
| `core-quarkus-extensions`               | 10.4.0-SNAPSHOT | set at release |
| `maas-client-quarkus`                   | 11.2.0-SNAPSHOT | set at release |
| `maas-declarative-client-quarkus`       | 9.2.0-SNAPSHOT  | set at release |
| `core-quarkus-external-bom`             | 1.2.0-SNAPSHOT  | set at release |
| `dbaas-client`                          | 9.3.0-SNAPSHOT  | set at release |

---

## Checklist

- [ ] Quarkus version updated to `3.40.1.1` in all pom.xml files that declare it
- [ ] `AgroalConnectionFactoryConfiguration.networkTimeout()` implemented (if project implements it)
- [ ] New `AgroalConnectionPoolConfiguration` methods implemented (if project implements it)
- [ ] `byte-buddy` is on the test classpath of modules that use Mockito
- [ ] Values from `getConfigOverrides()` are not expected in `System.getProperty`
- [ ] Test resources that return config do not rely on it with `quarkus.test.flat-class-path=true`
- [ ] Declared versions from section 6 raised; no direct `jandex` version below the BOM
- [ ] REST Assured tests pass; no manual `quarkus.servlet.context-path` in their URLs
- [ ] Database integration tests pass on Hibernate ORM 7.4, including native queries on a real database
- [ ] PostgreSQL is version 14 or newer in every environment
- [ ] No blocking `@Transactional` endpoint returning `Uni` without `@Blocking`
- [ ] No `NULL` values in `@Version` / `@CreationTimestamp` / `@UpdateTimestamp` columns if Hibernate manages the schema
- [ ] `Host` validation reviewed for applications with `quarkus.http.host=localhost`
- [ ] No metric name registered with different tag keys (including DBaaS tenant vs service databases)
- [ ] `dbaas-cassandra-client` with Prometheus and more than one kind of database: fixed library, or metrics off
- [ ] Interceptor bindings on `default` methods reviewed
- [ ] Controllers without generation-aware processing reviewed; `operator-framework-junit-5` renamed or removed
- [ ] No `new PersistentBag(null, ...)` (or similar) with a `null` session
- [ ] Library versions match Quarkus 3.40 (no mix with libraries built for 3.33)
- [ ] `mvn verify` passes in all modules, including tests that need Docker

---

## References

- [Migration Guide 3.34](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.34)
- [Migration Guide 3.35](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.35)
- [Migration Guide 3.36](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.36)
- [Migration Guide 3.37](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.37)
- [Migration Guide 3.38](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.38)
- [Migration Guide 3.39](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.39)
- [Migration Guide 3.40](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.40)
- [Hibernate ORM 7.3 Migration Guide](https://docs.hibernate.org/orm/7.3/migration-guide/)
- [Hibernate ORM 7.4 Migration Guide](https://docs.hibernate.org/orm/7.4/migration-guide/)
- [Java Operator SDK: migrating to v5.3](https://javaoperatorsdk.io/docs/migration/v5-3-migration/)
- [Java Operator SDK: migrating to v5.5](https://javaoperatorsdk.io/docs/migration/v5-5-migration/)
