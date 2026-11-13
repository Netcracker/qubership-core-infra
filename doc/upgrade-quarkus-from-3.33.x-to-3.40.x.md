# Quarkus 3.33 → 3.40 Migration Guide

Quarkus 3.40 is an LTS release. Breaking changes come from intermediate versions 3.34–3.39, not from 3.40 itself.

This guide covers confirmed findings on Qubership Quarkus libraries and `qubership-dbaas`: changes that affect our
artifacts, their signatures, or runtime behaviour that consumers see. Everything else stays in the [official Quarkus
migration guides](#references).

For Quarkus applications, prefer `quarkus update`. For extension libraries (`runtime` + `deployment`), apply the steps
below by hand.

---

## 1. Update Quarkus version

Find and update Quarkus version properties to `3.40.1.1`:

```bash
grep -rnE "<quarkus(\.platform)?\.version>" --include="pom.xml"
```

---

## 2. New abstract methods in Agroal 3.2.1

Quarkus 3.40 ships Agroal 3.2.1.

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
connection is created (`dbaas-datasource-postgresql` is affected). Update the library together with Quarkus (see
[Note for library consumers](#note-for-library-consumers)).

```bash
grep -rnE "implements .*AgroalConnectionFactoryConfiguration" --include="*.java"
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

From 3.34. Overrides from `getConfigOverrides()` are visible through MicroProfile Config only, not through
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

With `quarkus.test.flat-class-path=true`, values returned from
`QuarkusTestResourceLifecycleManager.start()` are dropped. `QuarkusTestProfile.getConfigOverrides()` is not affected.
Verified with real Consul and Cassandra containers.

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

Raise declared versions that sit below the Quarkus BOM:

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

```bash
grep -rnE "(2\.13\.2|33\.5\.0-jre|2\.21\.0|26\.0\.2|7\.5\.2|3\.9\.12|3\.2\.1\.Final)<" --include="pom.xml"
grep -rn -A1 "<artifactId>jandex</artifactId>" --include="pom.xml"
```

Versions left unchanged on purpose: `amqp-client` `5.37.0`, `log4j-*` `2.26.1`, `junit-bom` `6.0.3`.

---

## 7. Repeated registration of a Prometheus metric (from 3.34)

**Confirmed** on `qubership-dbaas` and on the libraries. This changes production behaviour, not only tests.

**Symptom:**

```
java.lang.IllegalArgumentException: Prometheus requires that all meters with the same name have the same set of tag keys.
```

From 3.34 `PrometheusMeterRegistryProducer` creates the registry with `throwExceptionOnRegistrationFailure()`. Before,
the second registration was silently dropped. Spring Boot 4.1.2 does not turn this on.

**DBaaS clients** (`DatabaseMetricProperties.getMetricTags()`): a tenant database has more tag keys than a service
database. On Quarkus with `quarkus-micrometer-registry-prometheus` and more than one kind of database:

| Client | Before the fix | Now |
|---|---|---|
| `dbaas-datasource-postgresql` | creating the second kind of database fails after its migration, and its data source stays open | The registration failure is logged, the database works, its metrics are absent, as before 3.34 |
| `dbaas-opensearch-client` | From the code: each request registers a timer, so the request fails | The failure is logged as a warning, the request works, the metric is absent |
| `dbaas-cassandra-client` | without a server: the driver registers meters in `Metrics.globalRegistry` while it creates a session, outside `DbaaSMetricsRegistrar`. Creating a session for the second kind of database fails if session metrics are enabled, and hangs if only node metrics are enabled | The failure is logged as an error and the session connects without metrics of that session or node. Without the fix: `quarkus.dbaas.cassandra.metrics.enabled=false` |

Metrics of the second kind of database stay absent until tag keys are unified. `maas-kafka-quarkus-client` needs no
change (`KafkaClientMetrics` already catches the failure).

Use library versions that include this fix (see [Affected Qubership Libs](#affected-qubership-libs)).

```bash
grep -rnE "MeterRegistry|Metrics\.(counter|timer|gauge)|Counter\.builder|Timer\.builder|Gauge\.builder" --include="*.java"
```

---

## 8. `new PersistentBag(null, ...)` fails on Hibernate ORM 7.4

**Confirmed** on `qubership-dbaas`. Compiles, fails at runtime. Not mentioned in the Quarkus guides.

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

- Use library versions built for Quarkus 3.40 together with Quarkus 3.40. Libraries built for 3.33 fail at runtime on
  Agroal (`AbstractMethodError: ...networkTimeout()`, [section 2.1](#21-agroalconnectionfactoryconfigurationnetworktimeout)).
- `dbaas-client` includes the Prometheus registration fix ([section 7](#7-repeated-registration-of-a-prometheus-metric-from-334)).

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
- [ ] `AgroalConnectionFactoryConfiguration.networkTimeout()` implemented (if the project implements the interface)
- [ ] `byte-buddy` is on the test classpath of modules that use Mockito
- [ ] Values from `getConfigOverrides()` are not expected in `System.getProperty`
- [ ] Test resources that return config do not rely on it with `quarkus.test.flat-class-path=true`
- [ ] Declared versions from section 6 raised; no direct `jandex` version below the BOM
- [ ] `dbaas-client` with Prometheus and more than one kind of database: fixed library, or Cassandra metrics off
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
