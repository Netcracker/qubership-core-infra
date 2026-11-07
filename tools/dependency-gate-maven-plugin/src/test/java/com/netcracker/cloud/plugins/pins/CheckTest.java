package com.netcracker.cloud.plugins.pins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.plugins.pins.PinsEvaluator.Finding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckTest {

    private static final String TOOL = "dependency-gate-maven-plugin 1.2.0";

    @TempDir
    static Path tmp;

    private static Map<String, Finding> repo;
    private static Map<String, Finding> tree;
    private static Map<String, JsonNode> written;

    @BeforeAll
    static void check() throws Exception {
        repo = Fixtures.check();
        UsageCollector collector = Fixtures.collect(Fixtures.REPO);
        collector.add(resolved("com.fasterxml.jackson.core:jackson-databind", "2.21.8"));
        collector.add(resolved("com.fasterxml.jackson.core:jackson-core", "2.21.6"));
        collector.add(resolved("io.quarkus:quarkus-core", "3.33.5"));
        collector.add(resolved("com.rabbitmq:amqp-client", "5.37.0"));
        collector.add(resolved("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter", "2.28.1"));
        tree = Fixtures.byKey(Fixtures.check(collector));
        assertTrue(repo.values().stream().noneMatch(Finding::transitive));

        List<Finding> findings = Fixtures.check(Fixtures.collect(Fixtures.REPO));
        findings.add(tree.get("pin-exceeded|io.quarkus:quarkus-core|3.33.5"));
        JsonNode file = write(FindingsFile.ok(TOOL, "head", findings, null));
        assertEquals("ok", file.path("adapter").path("status").asText());
        assertFalse(file.path("adapter").has("reason"));
        assertEquals(PinsEvaluator.CHECKS, StreamSupport.stream(file.path("adapter").path("checks").spliterator(),
                false).map(JsonNode::asText).toList());
        written = StreamSupport.stream(file.path("findings").spliterator(), false)
                .collect(Collectors.toMap(n -> n.path("check").asText() + "|" + n.path("package").asText() + "|"
                        + n.path("version").asText() + (n.path("transitive").asBoolean() ? "|transitive" : ""),
                        n -> n));
        assertEquals(findings.size(), written.size());
    }

    private static JsonNode write(FindingsFile file) throws IOException {
        Path path = Files.createTempDirectory(tmp, "findings").resolve("dependency-gate").resolve("maven-pins.json");
        file.write(path);
        JsonNode root = new ObjectMapper().readTree(path.toFile());
        assertEquals("dependency-gate-findings/v1", root.path("schema").asText());
        assertEquals("maven-pins", root.path("adapter").path("name").asText());
        assertEquals("maven", root.path("adapter").path("ecosystem").asText());
        return root;
    }

    private static Map<String, Finding> evaluate(Usage... usages) throws Exception {
        return Fixtures.byKey(new PinsEvaluator(Fixtures.pins()).evaluate(List.of(usages), Set.of()));
    }

    private static Usage declared(String ga, String version, Usage.Kind kind) {
        return Usage.of(ga, version, kind, Usage.Source.DECLARED, "app/pom.xml", 12, "app/pom.xml");
    }

    private static Usage managedBy(String ga, String version, String bom) {
        return new Usage(ga, version, Usage.Kind.MANAGED, Usage.Source.EFFECTIVE, bom, 40, List.of(), null, 0,
                List.of(bom), "app/pom.xml");
    }

    private static Usage resolved(String ga, String version) {
        return Usage.of(ga, version, Usage.Kind.RESOLVED, Usage.Source.RESOLVED,
                "dependency tree of com.netcracker.cloud:fixture-lib (compile)", 0, "lib/pom.xml");
    }

    @ParameterizedTest(name = "{0} {1}:{2}")
    @CsvSource({
            "pin-exceeded,     com.fasterxml.jackson:jackson-bom,                 2.21.7",
            "pin-exceeded,     com.rabbitmq:amqp-client,                          5.37.0",
            "pin-exceeded,     io.quarkus:quarkus-maven-plugin,                   3.40.1",
            "pin-mismatch,     org.apache.maven.plugins:maven-surefire-plugin,    3.6.0",
            "pin-exceeded,     org.projectlombok:lombok,                          1.18.48",
            "pin-lagging,      org.slf4j:slf4j-api,                               2.0.17",
            "feature-snapshot, com.netcracker.cloud:consul-config-provider-common, 7.5.3-feat-consul-auth-mode-SNAPSHOT",
            "pin-exceeded,     io.quarkus:quarkus-core,                           3.40.1",
            "pin-exceeded,     org.springframework.boot:spring-boot-dependencies, 4.1.2",
            "pin-conflict,     io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter, 2.28.1",
            "snapshot,         com.netcracker.cloud:fixture-other,                2.2.7-SNAPSHOT",
            "shared-property,  ${quarkus.version},                                3.40.1",
            // matching pins / own modules / versions folded into a BOM note: no finding of their own
            "none,             com.google.guava:guava,                            ",
            "none,             com.netcracker.cloud:fixture-bom,                  ",
            "none,             org.springframework.boot:spring-boot,              "})
    void found(String check, String ga, String version) {
        if (check.equals("none")) {
            assertEquals(Set.of(), repo.values().stream().filter(f -> f.ga().equals(ga)).map(Finding::check)
                    .collect(Collectors.toSet()), repo.keySet().toString());
            return;
        }
        assertTrue(repo.containsKey(Finding.key(check, ga, version)), repo.keySet().toString());
    }

    @ParameterizedTest(name = "{0} {1}:{2} note has {3}")
    @CsvSource({
            "pin-exceeded, org.springframework.boot:spring-boot-dependencies, 4.1.2, dependencies/spring-dependencies",
            "pin-exceeded, org.springframework.boot:spring-boot-dependencies, 4.1.2, it also brings 3 managed version(s)",
            "pin-exceeded, org.springframework.boot:spring-boot-dependencies, 4.1.2, org.springframework.boot:spring-boot:4.1.2",
            "pin-exceeded, org.springframework.boot:spring-boot-dependencies, 4.1.2, org.springframework:spring-core:7.0.10",
            "pin-conflict, io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter, 2.28.1, opentelemetry-instrumentation-bom:2.28.1"})
    void noted(String check, String ga, String version, String text) {
        String note = repo.get(Finding.key(check, ga, version)).note();
        assertTrue(note.contains(text), note);
    }

    @Test
    void sharedPropertyNamesPinnedAndUnpinnedArtifacts() {
        String message = repo.get("shared-property|${quarkus.version}|3.40.1").message();
        assertTrue(message.contains("quarkus-universe-bom"), message);
        assertFalse(message.contains("quarkus-operator-sdk-bom"), message);
    }

    @Test
    void belowThePinOnlyWhereDeclared() {
        assertTrue(repo.get("pin-lagging|org.slf4j:slf4j-api|2.0.17").usages().stream()
                .allMatch(u -> u.source() == Usage.Source.DECLARED));
    }

    @Test
    void bomBringsAlongOnlyFindingsOfItsOwnCheck() throws Exception {
        Map<String, Finding> f = evaluate(declared("org.example:other-bom", "2.0.0-SNAPSHOT", Usage.Kind.IMPORT),
                managedBy("com.rabbitmq:amqp-client", "5.37.0", "org.example:other-bom:2.0.0-SNAPSHOT"));
        assertEquals(Set.of("snapshot|org.example:other-bom|2.0.0-SNAPSHOT",
                "pin-exceeded|com.rabbitmq:amqp-client|5.37.0"), f.keySet());
        assertEquals("", f.get("snapshot|org.example:other-bom|2.0.0-SNAPSHOT").note());
    }

    @Test
    void featureSnapshotBomBringsAlongItsFeatureSnapshots() throws Exception {
        Map<String, Finding> f = evaluate(declared("org.example:other-bom", "2.0.0-feat-x-SNAPSHOT", Usage.Kind.IMPORT),
                managedBy("com.rabbitmq:amqp-client", "5.37.0", "org.example:other-bom:2.0.0-feat-x-SNAPSHOT"),
                managedBy("org.example:other", "2.0.0-feat-x-SNAPSHOT", "org.example:other-bom:2.0.0-feat-x-SNAPSHOT"));
        assertEquals(Set.of("feature-snapshot|org.example:other-bom|2.0.0-feat-x-SNAPSHOT",
                "pin-exceeded|com.rabbitmq:amqp-client|5.37.0"), f.keySet());
        assertTrue(f.get("feature-snapshot|org.example:other-bom|2.0.0-feat-x-SNAPSHOT").note()
                .contains("org.example:other:2.0.0-feat-x-SNAPSHOT"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            managed by a BOM                  | IMPORT | managed                  | true
            used without a version            | IMPORT | managed;used             | true
            in the dependency tree too        | IMPORT | managed;used;tree        | true
            managed by a parent               | PARENT | managed;tree             | true
            only in the dependency tree       | IMPORT | tree                     | false
            declared in the repository too    | IMPORT | managed;used;tree;declared | false
            managed in the repository too     | IMPORT | managed;repository;tree  | false
            managed by another BOM too        | IMPORT | managed;other;tree       | false""")
    void versionFromFailingBomFoldsIntoItsFinding(String name, Usage.Kind kind, String usages, boolean folded)
            throws Exception {
        String ga = "com.rabbitmq:amqp-client";
        String version = "5.37.0";
        String bom = "org.springframework.boot:spring-boot-dependencies:4.1.2";
        List<Usage> all = new ArrayList<>(List.of(declared("org.springframework.boot:spring-boot-dependencies", "4.1.2",
                kind)));
        for (String usage : usages.split(";")) {
            all.add(switch (usage) {
                case "managed" -> managedBy(ga, version, bom);
                case "used" -> new Usage(ga, version, Usage.Kind.DEPENDENCY, Usage.Source.EFFECTIVE, bom, 40, List.of(),
                        null, 0, List.of(bom), "app/pom.xml");
                case "tree" -> resolved(ga, version);
                case "declared" -> declared(ga, version, Usage.Kind.DEPENDENCY);
                case "repository" -> Usage.of(ga, version, Usage.Kind.MANAGED, Usage.Source.EFFECTIVE, "bom/pom.xml", 10,
                        "app/pom.xml");
                case "other" -> managedBy(ga, version, "org.example:other-bom:1.0");
                default -> throw new IllegalArgumentException(usage);
            });
        }
        Map<String, Finding> f = evaluate(all.toArray(Usage[]::new));
        String note = f.get("pin-exceeded|org.springframework.boot:spring-boot-dependencies|4.1.2").note();
        assertEquals(folded, note.contains(ga + ":" + version), note);
        assertEquals(!folded, f.containsKey(Finding.key("pin-exceeded", ga, version)), f.keySet().toString());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
            "7.5.3-feat-consul-auth-mode-SNAPSHOT,                feature-snapshot",
            "1.2.0-feature-dependency-pins-maven-plugin-SNAPSHOT, feature-snapshot",
            "1.0.0-RC1-SNAPSHOT,                                  snapshot",
            "33.5.0-jre-SNAPSHOT,                                 snapshot",
            "1.0-feat-x-SNAPSHOT,                                 snapshot"})
    void onlyBranchSnapshotsAreFeatureSnapshots(String version, String check) throws Exception {
        assertEquals(Set.of(check + "|org.example:a|" + version),
                evaluate(declared("org.example:a", version, Usage.Kind.DEPENDENCY)).keySet());
    }

    // pattern pins: below / negated only when declared; above the leading numbers also from BOM / tree
    @ParameterizedTest(name = "{0}:{1} {2}: {3}")
    @CsvSource({
            "io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom, 2.27.0,     DECLARED,  pin-mismatch",
            "io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom, 2.27.0,     EFFECTIVE, ''",
            "io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom, 2.29.0,     RESOLVED,  pin-mismatch",
            "org.apache.maven.plugins:maven-surefire-plugin,                     3.6.0,      DECLARED,  pin-mismatch",
            "org.apache.maven.plugins:maven-surefire-plugin,                     3.6.0,      EFFECTIVE, ''"})
    void patternPin(String ga, String version, Usage.Source source, String check) throws Exception {
        Usage usage = switch (source) {
            case DECLARED -> declared(ga, version, Usage.Kind.DEPENDENCY);
            case EFFECTIVE -> managedBy(ga, version, "org.example:other-bom:1.0");
            case RESOLVED -> resolved(ga, version);
        };
        assertEquals(check.isEmpty() ? Set.of() : Set.of(Finding.key(check, ga, version)), evaluate(usage).keySet());
    }

    @ParameterizedTest(name = "{0} {1}:{2}: {3}")
    @CsvSource({
            "pin-exceeded, com.fasterxml.jackson.core:jackson-databind,  2.21.8, transitive",
            "pin-exceeded, io.quarkus:quarkus-core,                      3.33.5, transitive",
            "pin-exceeded, com.rabbitmq:amqp-client,                     5.37.0, direct",
            "pin-conflict, io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter, 2.28.1, direct",
            "pin-lagging,  com.fasterxml.jackson.core:jackson-core,      2.21.6, not reported"})
    void resolvedDependencyTree(String check, String ga, String version, String reported) {
        Finding f = tree.get(Finding.key(check, ga, version));
        if (reported.equals("not reported")) {
            assertNull(f);
            return;
        }
        assertNotNull(f, tree.keySet().toString());
        assertEquals(reported.equals("transitive"), f.transitive());
    }

    @ParameterizedTest(name = "{0}: {1}:{2}")
    @CsvSource({
            "pin-exceeded|com.rabbitmq:amqp-client|5.37.0,           bom/pom.xml,         10",
            "pin-exceeded|io.quarkus:quarkus-core|3.40.1,            quarkus-app/pom.xml, 26",
            "pin-exceeded|io.quarkus:quarkus-core|3.33.5|transitive, lib/pom.xml,           "})
    void firstLocation(String key, String file, Integer line) {
        JsonNode location = written.get(key).path("locations").get(0);
        assertEquals(file, location.path("file").asText());
        assertEquals(line != null, location.has("line"));
        if (line != null) {
            assertEquals(line, location.path("line").asInt());
        }
    }

    @Test
    void emptyFieldsAreLeftOut() {
        JsonNode amqp = written.get("pin-exceeded|com.rabbitmq:amqp-client|5.37.0");
        assertFalse(amqp.has("transitive"));
        assertFalse(amqp.has("note"));
    }

    @Test
    void failedCheckIsReportedToTheGate() throws Exception {
        JsonNode root = write(FindingsFile.error(TOOL, "base", "Cannot read the pins"));
        assertEquals("error", root.path("adapter").path("status").asText());
        assertEquals("Cannot read the pins", root.path("adapter").path("reason").asText());
        assertTrue(root.path("adapter").path("checks").isArray());
        assertTrue(root.path("findings").isEmpty());
        assertEquals("base", root.path("revision").asText());
    }

    @Test
    void incompleteDependencyTreeIsShownToTheGate() throws Exception {
        String reason = "incomplete dependency tree, transitive findings may be missing: g:a: no versions";
        JsonNode root = write(FindingsFile.ok(TOOL, "head", List.of(), reason));
        assertEquals("ok", root.path("adapter").path("status").asText());
        assertEquals(reason, root.path("adapter").path("reason").asText());
        assertEquals("head", root.path("revision").asText());
    }

    @Test
    void cleanRepoHasNoFindings(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>
                <version>1.0.0-SNAPSHOT</version><dependencies><dependency><groupId>com.rabbitmq</groupId>
                <artifactId>amqp-client</artifactId><version>5.30.0</version></dependency></dependencies></project>""");
        assertEquals(List.of(), Fixtures.check(Fixtures.collect(dir)));
    }
}
