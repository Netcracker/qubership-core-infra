# Description

In Quarkus 3.27.x the old configuration model based on mutable classes with @ConfigRoot and @ConfigItem is deprecated.
Quarkus now relies on @ConfigMapping and interface-based configuration. Because of this, configuration classes must be
converted to interfaces, fields must be replaced with methods, annotations must be updated, and unit tests must be
adjusted. This guide explains these required changes and shows how to migrate existing code step by step.

# Quarkus part

## pom.xml

- Update version of all quarkus libs to 3.27.1
- Find and remove all `legacyConfigRoot=true` arguments from the `maven-compiler-plugin` configuration.

```xml

<configuration>
    <compilerArgs>
        <arg>-AlegacyConfigRoot=true</arg>
    </compilerArgs>
</configuration>
```

## Configuration Mapping

### `@ConfigRoot`

1. Find all classes annotated with `io.quarkus.runtime.annotations.@ConfigRoot`.
2. Convert these classes to interfaces.
3. Replace the `@ConfigRoot` annotation on the class with a combination of `@ConfigRoot` and
   `io.smallrye.config.@ConfigMapping`.

| Old Configuration                                                         | New Configuration                                                                   | Comment                                                                                                                                                                   |
|---------------------------------------------------------------------------|-------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@ConfigRoot(prefix = "foo", name = "bar", phase = ConfigPhase.RUN_TIME)` | `@ConfigMapping(prefix = "foo.bar")``@ConfigRoot(phase = ConfigPhase.RUN_TIME)`     | If both `prefix` and `name` are specified in `@ConfigRoot`, concatenate their values and put them into the `@ConfigMapping(prefix = ...)`. Keep `phase` in `@ConfigRoot`. |
| `@ConfigRoot(name = "bar", phase = ConfigPhase.RUN_TIME)`                 | `@ConfigMapping(prefix = "quarkus.bar")``@ConfigRoot(phase = ConfigPhase.RUN_TIME)` | If `prefix` is not specified, use the default prefix `quarkus`.                                                                                                           |
| `@ConfigRoot(name = "", phase = ConfigPhase.RUN_TIME)`                    | `@ConfigMapping(prefix = "quarkus")``@ConfigRoot(phase = ConfigPhase.RUN_TIME)`     | If `name` is blank, use only the prefix.                                                                                                                                  |
| `@ConfigRoot``public class FooBar`                                        | `@ConfigMapping(prefix = "quarkus.foo-bar")``@ConfigRoot``public interface FooBar`  | If `name` is not specified at all in `@ConfigRoot`, use the hyphenated class name.                                                                                        |

4. Convert all fields into methods with the same names. Find all usages and update them accordingly.
5. Replace the `@ConfigItem` annotation on fields.

| Old Configuration                                 | New Configuration                       | Comment                                                                                |
|---------------------------------------------------|-----------------------------------------|----------------------------------------------------------------------------------------|
| `@ConfigItem`                                     | _removed_                               | Just remove it.                                                                        |
| `@ConfigItem(name = "foo", defaultValue = "bar")` | `@WithName("foo")``@WithDefault("bar")` | The `name` parameter moves to `@WithName`, and `defaultValue` moves to `@WithDefault`. |
| `@ConfigItem(name = ConfigItem.PARENT)`           | `@WithParentName`                       | `@ConfigItem` with `name = ConfigItem.PARENT` is transformed into `@WithParentName`.   |

6. Replace all `@ConfigMapping` annotations on fields.

| Old Configuration                | New Configuration  | Comment                                                                     |
|----------------------------------|--------------------|-----------------------------------------------------------------------------|
| `@ConfigMapping(prefix = "foo")` | `@WithName("foo")` | Replace `@ConfigMapping` with a `prefix` parameter by `@WithName`.          |
| `@ConfigMapping`                 | `@WithParentName`  | Replace `@ConfigMapping` without a `prefix` parameter by `@WithParentName`. |

7. Ensure that all methods have JavaDoc comments.

### `@ConfigGroup`

1. Find all classes annotated with `io.quarkus.runtime.annotations.@ConfigGroup`.
2. Convert these classes to interfaces.
3. Remove the `@ConfigGroup` annotation.
4. Ensure that all methods have JavaDoc comments.

### Unit Tests

You will need to fix your tests, especially if you were instantiating classes annotated with `@ConfigRoot`. Since these
are now interfaces, you must use Mockito to create mocks and stub method behavior.

**Before:**

```java
FlywayConfig flywayConfig = new FlywayConfig();
flywayConfig.cleanAndMigrateAtStart =false;
flywayConfig.ignoreMigrationPatterns =Optional.

of(new String[] {
    "*:foo", "*:bar"
});
```

**After:**

```java
FlywayConfig flywayConfig = mock(FlywayConfig.class);

when(flywayConfig.cleanAndMigrateAtStart()).

thenReturn(false);

when(flywayConfig.ignoreMigrationPatterns()).

thenReturn(Optional.of(new String[] {
    "*:foo", "*:bar"
}));
```

## Miscellaneous

- Replace the constant `io.quarkus.vertx.http.deployment.FilterBuildItem.AUTHENTICATION` with
  `io.quarkus.vertx.http.runtime.security.SecurityHandlerPriorities.AUTHENTICATION`.

# Qubership part

Due to changes in Quarkus’s configuration model, we had to introduce breaking Java contract changes in several Qubership
libraries. In most cases, this involved migrating configuration classes to interfaces and replacing direct field access
with methods of the same name.

From the client’s perspective, CI behavior remains unchanged — configuration instances can still be injected as before.
However, any direct field access must now be replaced with method calls. In some cases, adjustments also affect nested
classes: instead of accessing a field on a nested class, you may now need to call a method on the parent class.

Affected Quberhip libs:

| Lib                                             | Old version | New version |
|-------------------------------------------------|-------------|-------------|
| qubership-core-context-propagation-quarkus      | 6.x.x       | 7.0.1       |
| qubership-core-quarkus-extensions               | 7.x.x       | 8.0.2       |
| qubership-core-blue-green-state-monitor-quarkus | 1.x.x       | 2.0.2       | 
| qubership-maas-client-quarkus                   | 8.x.x       | 9.0.2       | 
| qubership-maas-declarative-client-quarkus       | 6.x.x       | 7.0.2       |