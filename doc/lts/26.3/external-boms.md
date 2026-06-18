# External BOMs — Release Notes

Cloud Core libraries are now distributed through three independently-versioned external BOMs, one per technology stack. Each BOM lists all relevant Cloud Core libraries with aligned versions, so consumers depend on a single BOM and update only its version on each release instead of bumping every library individually.

# BOMs

- **`cloud-core-java-bom`** — framework-agnostic Java libraries; foundation for the other two BOMs.
- **Spring BOM** — Spring Boot stack (framework extensions, REST libraries); imports `cloud-core-java-bom`.
- **Quarkus BOM** — Quarkus stack; manages both the `runtime` and the `*deployment` artifact of each extension; imports `cloud-core-java-bom`.

Aggregators, parent POMs, and unpublished modules are not included in the external BOMs.

# How to use

Import the relevant BOM in your `dependencyManagement`, then declare Cloud Core libraries without versions:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.netcracker.cloud</groupId>
      <artifactId>cloud-core-java-bom</artifactId>
      <version>VERSION</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

# More info

Full structure, coordinates, and sub-BOM import strategy: https://github.com/Netcracker/qubership-core-java-libs/blob/main/core-external-boms/cloud-core-external-boms-structure.md