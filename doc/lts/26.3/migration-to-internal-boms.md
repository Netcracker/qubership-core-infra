# Internal BOM Migration — Release Notes

Three new internal BOMs have been introduced to centralize third-party dependency version management across the monorepo:

- `qubership-java-internal-bom` — framework-agnostic libraries (slf4j, lombok, jackson, junit, mockito, etc.)
- `qubership-spring-internal-bom` — Spring Boot / Spring Cloud stack, imports `qubership-java-internal-bom`
- `qubership-quarkus-internal-bom` — Quarkus stack, imports `qubership-java-internal-bom`

All modules have been migrated to use these BOMs instead of managing third-party versions individually.

# What changes for consumers

## `com.netcracker.cloud.junit.cloudcore:cloud-core-extension`

- **`javax.validation:validation-api` is no longer a transitive dependency.**
This artifact is part of the legacy `javax.*` namespace and has been superseded by `jakarta.validation:jakarta.validation-api`. It will not be provided transitively going forward. If you still need it, add it explicitly to your `pom.xml`.

- **`org.projectlombok:lombok` is no longer a transitive dependency.**
Lombok is an annotation processor and should not propagate to consumers. Add it explicitly:

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>x.x.x</version>
    <scope>provided</scope>
</dependency>
```
