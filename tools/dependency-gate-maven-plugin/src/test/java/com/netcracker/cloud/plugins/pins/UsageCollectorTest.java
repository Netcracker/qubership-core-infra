package com.netcracker.cloud.plugins.pins;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageCollectorTest {

    @TempDir
    static Path tmp;

    // repo: the fixture reactor; maven-order: properties overridden by a child profile and by -D
    private static Map<String, UsageCollector> collectors;

    @BeforeAll
    static void collect() throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId><artifactId>parent</artifactId><version>1.0.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules><module>child</module></modules>
                  <properties>
                    <amqp.version>5.30.0</amqp.version>
                    <lombok.version>1.18.46</lombok.version>
                  </properties>
                </project>""");
        Files.createDirectories(tmp.resolve("child"));
        Files.writeString(tmp.resolve("child/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>g</groupId><artifactId>parent</artifactId><version>1.0.0-SNAPSHOT</version></parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency><groupId>com.rabbitmq</groupId><artifactId>amqp-client</artifactId>
                      <version>${amqp.version}</version></dependency>
                    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
                      <version>${lombok.version}</version></dependency>
                  </dependencies>
                  <profiles>
                    <profile>
                      <id>inactive</id>
                      <activation><property><name>never</name></property></activation>
                      <properties><amqp.version>5.31.0</amqp.version></properties>
                    </profile>
                    <profile>
                      <id>default</id>
                      <activation><activeByDefault>true</activeByDefault></activation>
                      <properties>
                        <amqp.version>5.37.0</amqp.version>
                      </properties>
                    </profile>
                  </profiles>
                </project>""");
        Properties user = new Properties();
        user.setProperty("lombok.version", "1.18.48");
        collectors = Map.of("repo", Fixtures.collect(Fixtures.REPO), "maven-order", Fixtures.collect(tmp, user));
    }

    private static List<Usage> find(String ga, Usage.Source source) {
        return collectors.get("repo").usages().stream().filter(u -> u.ga().equals(ga) && u.source() == source)
                .toList();
    }

    /**
     * {@code where}: the property definition to change, if any, then the declaration.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # project   | GA                                             | version | kind                 | properties                               | where
            repo        | com.rabbitmq:amqp-client                       | 5.37.0  | MANAGED              | rabbitmq.version                         | bom/pom.xml:10 (${rabbitmq.version}) <- bom/pom.xml:34
            repo        | io.quarkus:quarkus-universe-bom                | 3.40.1  | IMPORT               | quarkus.platform.version quarkus.version | quarkus-app/pom.xml:8 (${quarkus.version}) <- quarkus-app/pom.xml:16
            repo        | org.apache.maven.plugins:maven-surefire-plugin | 3.6.0   | PLUGIN               | surefire.version                         | lib/pom.xml:12 (${surefire.version}) <- lib/pom.xml:35
            repo        | org.projectlombok:lombok                       | 1.18.48 | ANNOTATION_PROCESSOR |                                          | lib/pom.xml:45
            # an active profile of the child overrides the parent
            maven-order | com.rabbitmq:amqp-client                       | 5.37.0  | DEPENDENCY           | amqp.version                             | child/pom.xml:21 (${amqp.version}) <- child/pom.xml:7
            # -D overrides the model and has no line to change in the repository
            maven-order | org.projectlombok:lombok                       | 1.18.48 | DEPENDENCY           | lombok.version                           | child/pom.xml:9""")
    void declared(String project, String ga, String version, Usage.Kind kind, String properties, String where) {
        List<Usage> found = collectors.get(project).usages().stream()
                .filter(u -> u.ga().equals(ga) && u.source() == Usage.Source.DECLARED).toList();
        assertEquals(1, found.size(), found.toString());
        Usage u = found.getFirst();
        assertAll(() -> assertEquals(version, u.version()),
                () -> assertEquals(kind, u.kind()),
                () -> assertEquals(properties == null ? List.of() : Arrays.asList(properties.split(" ")),
                        u.properties()),
                () -> assertEquals(where, u.where()));
    }

    @Test
    void onlyReactorModulesAreScanned() {
        UsageCollector repo = collectors.get("repo");
        assertTrue(repo.own().contains("com.netcracker.cloud:fixture-lib"));
        assertFalse(repo.own().contains("x:ignored-test-fixture"));
        assertTrue(repo.usages().stream().noneMatch(u -> "99.0.0".equals(u.version())));
    }

    @Test
    void versionlessDependencyIsOnlyEffective() {
        assertEquals(List.of(), find("com.fasterxml.jackson.core:jackson-databind", Usage.Source.DECLARED));
        List<Usage> effective = find("com.fasterxml.jackson.core:jackson-databind", Usage.Source.EFFECTIVE);
        assertTrue(effective.stream().anyMatch(u -> u.kind() == Usage.Kind.DEPENDENCY && "2.21.7".equals(u.version())
                && u.via().equals(List.of("com.fasterxml.jackson:jackson-bom:2.21.7"))), effective.toString());
    }

    @Test
    void effectiveVersionsInheritedFromReactorPointAtTheDeclaration() {
        // fixture-lib inherits the dependency management of fixture-bom
        List<Usage> amqp = find("com.rabbitmq:amqp-client", Usage.Source.EFFECTIVE);
        assertFalse(amqp.isEmpty());
        assertTrue(amqp.stream().allMatch(u -> u.where().equals("bom/pom.xml:34") && u.via().isEmpty()),
                amqp.toString());
    }
}
