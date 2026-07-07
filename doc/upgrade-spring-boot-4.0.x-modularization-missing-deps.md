# Upgrade Notes: `NoClassDefFoundError` after a Spring Boot 4.0.x / Spring Cloud bump

## When you need this

You bumped **Spring Boot** within the 4.0.x line (even a small patch like `4.0.6 → 4.0.7`) and/or **Spring Cloud** to a compatible version, the versions are officially compatible, but tests or startup now fail with something like:

```
NoClassDefFoundError: org/springframework/boot/http/client/ClientHttpRequestFactoryBuilder
NoClassDefFoundError: org/springframework/boot/restclient/RestTemplateBuilder
```

## Why it happens

In Spring Boot 3.x almost everything lived in one big `spring-boot` jar. In **Spring Boot 4.0 that jar was split into many small modules** (`spring-boot-http-client`, `spring-boot-restclient`, and others).

A class you used to get automatically now lives in a separate module. If nothing in your dependency tree pulls that module in, the class is simply missing at runtime — hence `NoClassDefFoundError`.

The upgrade makes this show up because either:
- a version change shifts your dependency tree and the module that happened to bring the class in disappears, or
- new upstream code (usually Spring Cloud) starts using a class that moved, without declaring the module it now lives in.

**This is not a version conflict.** Don't downgrade or pin versions. You just need to add the missing module.

## How to fix

1. Look at the missing class in the error: `org/springframework/boot/<something>/<Class>`.
2. Add the Spring Boot module that contains it. The module name usually matches the package — for example `http.client` → `spring-boot-http-client`, `restclient` → `spring-boot-restclient`.
3. **Don't set a version** — it's managed by `spring-boot-dependencies`.
4. Pick the scope by who needs the class:
   - needed when the app starts (production / auto-configuration) → normal (compile) scope
   - needed only by tests (e.g. `TestRestTemplate`) → `test` scope

## The two cases we hit

### 1. `ClientHttpRequestFactoryBuilder` is missing

Happens in modules that use a Spring Cloud component (e.g. `spring-cloud-config-client`). The class moved to `spring-boot-http-client`, and Spring Cloud uses it at startup but never declares that module.

Add (compile scope):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-http-client</artifactId>
</dependency>
```

### 2. `RestTemplateBuilder` is missing in tests

Happens in tests that use `TestRestTemplate` (from `spring-boot-resttestclient`). `TestRestTemplate` needs `RestTemplateBuilder`, which moved to `spring-boot-restclient`, but `spring-boot-resttestclient` does not bring it in on its own.

Add (test scope):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-restclient</artifactId>
    <scope>test</scope>
</dependency>
```

---

Reference: [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).
