# Migration Guide: Java 21 / Spring Framework 7 / Spring Boot 4

## Overview

New major release upgrades the core framework to the next generation of the Spring ecosystem. All downstream microservices that depend on this libraries **must** be updated accordingly. The changes are primarily in three areas:

1. Platform / toolchain upgrades (Java, Spring, Spring Boot)
2. Internal application bootstrap changes (`MicroserviceApplicationBuilder`, `MicroserviceApplicationContext`)
3. Bean configuration changes (`BaseApplicationCommon`, `BaseApplicationOnWebClient`) — including a new default `SecurityFilterChain`

---
## Prerequisites — Upgrade Your Toolchain
Before updating the library version in your service, make sure your project meets the new baseline requirements:

| Component        | Previous | New (Required) |
|------------------|----------|----------------|
| Java             | 17       | 21             |
| Spring Framework | 6.x      | 7.x            | 
| Spring Boot      | 3.x      | 4.x            |


## 1. Spring Framework 7 / Spring Boot 4 Considerations

Spring Boot 4 is built on Spring Framework 7, which in turn requires Jakarta EE 11. Refer to the **[official Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)** for the full list of breaking changes. Key industry-wide migration points:

- All `javax.*` imports must already be `jakarta.*` (this was required since Spring Boot 3 / Spring Framework 6; ensure your code is already migrated).
- Review any use of deprecated Spring APIs that were removed in Framework 7.
- Spring Security 7 (bundled with Boot 4) may include breaking changes — consult the Spring Security migration guide if your service uses it.
- Spring Data, Spring Cloud, and other Spring ecosystem libraries must all be upgraded to versions compatible with Boot 4.

### 1.1 Jackson compatibility with Quarkus

Spring Boot 4 uses Jackson 3 by default. The Quarkus 3 extensions we use, including `quarkus-rest-jackson` and `quarkus-rest-client-jackson`, still integrate with Jackson 2. Jackson 3 uses `tools.jackson` for databind, so its mapper and modules cannot replace the `com.fasterxml.jackson.databind` types expected by Jackson 2 code. See the [Spring Boot migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide#upgrading-jackson) and the [Quarkus REST JSON guide](https://quarkus.io/guides/rest-json/#json).

Our shared Java libraries currently use Jackson 2 so the same artifacts can run in Spring Boot and Quarkus applications. For example, the MaaS client declares `com.fasterxml.jackson.core:jackson-databind`, and the core internal BOM manages Jackson 2 versions. See the [MaaS client POM](https://github.com/Netcracker/qubership-core-java-libs/blob/main/maas-client/client/pom.xml) and the [core internal BOM](https://github.com/Netcracker/qubership-core-java-libs/blob/main/core-internal-boms/core-internal-bom/pom.xml).

When upgrading a Spring application, keep Jackson 2 on the runtime classpath for libraries that use it. Both versions can coexist, so the application can still use Boot's default Jackson 3 mapper for its own JSON handling. If a Spring component needs a Jackson 2 `ObjectMapper` bean, Boot 4 can auto-configure one through the deprecated `org.springframework.boot:spring-boot-jackson2` module. The legacy REST client already declares this dependency. See the [Spring Boot JSON reference](https://docs.spring.io/spring-boot/4.0/reference/features/json.html#features.json.jackson2) and the [legacy REST client POM](https://github.com/Netcracker/qubership-core-java-libs/blob/main/core-rest-libraries/restlegacy/restclient/pom.xml).

---

## 2. Application Bootstrap Changes

### 2.1 `MicroserviceApplicationBuilder` — `initializers()` registration

The internal wiring of `MicroserviceApplicationContext` has changed. Previously it may have been registered via a different mechanism; it is now explicitly registered as a bean initializer on the `SpringApplicationBuilder`:

```java
.initializers(context ->
    ((GenericApplicationContext) context)
        .registerBean(MicroserviceApplicationContext.class,
                      filterPackagesToExclude,
                      filterClasses))
```

**Impact for users:** If your service extends or wraps `MicroserviceApplicationBuilder` or calls it with custom `initializers()`, be aware that an additional initializer is now pre-registered. Ensure your custom initializers do not conflict with this registration.

### 2.2 `MicroserviceApplicationContext` — Context refresh via `@EventListener`

Filter registration inside `MicroserviceApplicationContext` is now driven by a `@EventListener(ContextRefreshedEvent.class)` method rather than a direct lifecycle call.

**Impact for users:**
- If you have tests or code that relies on filters being registered before `ContextRefreshedEvent` fires, this ordering assumption no longer holds.
- Integration tests that use `@SpringBootTest` should not be affected because the event fires automatically during context startup.
- If you manually construct or refresh an `ApplicationContext` in tests, make sure to trigger context refresh so the event fires.

---

## 3. Bean Configuration Changes


### 3.1 `BaseApplicationOnWebClient` — `WebClient.Builder` bean is now conditional

A `WebClient.Builder` bean is now declared in `BaseApplicationOnWebClient` with `@ConditionalOnMissingBean`:

```java
@Bean
@ConditionalOnMissingBean
public WebClient.Builder webClientBuilder() { ... }
```

**Impact for users:**
- If your service already declares its own `WebClient.Builder` bean, it will take precedence and the framework's default will be skipped — this is the intended behaviour.
- If your service did **not** previously declare a `WebClient.Builder` bean and expected to receive one from elsewhere (e.g., Spring Boot auto-configuration), this framework-provided bean will now take over. Verify that the default builder configuration matches your requirements (base URLs, filters, codecs, etc.).
- If you rely on Spring Boot's own `WebClient.Builder` auto-configuration, be aware that the framework bean may shadow it. Declare your own `@Bean @Primary WebClient.Builder` if you need full control.

### 3.2 `BaseApplicationCommon` — Default `SecurityFilterChain` bean

`BaseApplicationCommon` now provides a default `SecurityFilterChain` bean:

```java
@Bean
@ConditionalOnMissingBean
SecurityFilterChain securityFilterChain(HttpSecurity http) {
   return http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable)
            .build();
}
```

This chain **permits all incoming requests** with no authentication or authorization checks. It is declared with `@ConditionalOnMissingBean`, so it is only active if your service does not define its own `SecurityFilterChain` bean.

---