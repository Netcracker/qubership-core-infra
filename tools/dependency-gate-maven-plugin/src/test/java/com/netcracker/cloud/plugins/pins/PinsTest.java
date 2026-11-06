package com.netcracker.cloud.plugins.pins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.plugins.pins.PinsEvaluator.Finding;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinsTest {

    private static Pins pins;

    @BeforeAll
    static void load() throws Exception {
        pins = Fixtures.pins();
    }

    private static Pins pins(String json) throws Exception {
        return new Pins(new ObjectMapper().readTree(json));
    }

    @ParameterizedTest(name = "{0}: exact {1}")
    @CsvSource({
            "com.rabbitmq:amqp-client,                        5.30.0",
            "org.apache.maven.plugins:maven-surefire-plugin,  ''"})
    void exact(String ga, String exact) {
        assertEquals(exact.isEmpty() ? null : exact, pins.constraintFor(ga).exact());
    }

    // exact pin matches as a regex: 5.30 equals 5.30.0 for Maven, but is not allowed
    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource({
            "com.rabbitmq:amqp-client,                        5.30.0,    true",
            "com.rabbitmq:amqp-client,                        5.30,      false",
            "com.rabbitmq:amqp-client,                        5.30.0-ga, false",
            "org.apache.maven.plugins:maven-surefire-plugin,  3.6.0,     false",
            "org.apache.maven.plugins:maven-surefire-plugin,  3.6.1,     true"})
    void allows(String ga, String version, boolean allowed) {
        assertEquals(allowed, pins.constraintFor(ga).allows(version));
    }

    @Test
    void patternOnlyRulesAreNotPins() {
        assertNull(pins.constraintFor("org.example:anything"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
            "io.quarkus.platform:quarkus-operator-sdk-bom, true",
            "io.quarkus.platform:quarkus-bom,              false"})
    void disabled(String ga, boolean disabled) {
        assertEquals(disabled, pins.isDisabled(ga));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({
            "com.fasterxml.jackson:jackson-bom,                  true",
            "org.springframework.boot:spring-boot-dependencies,  false"})
    void lintFindsBomBelowModules(String bom, boolean drift) {
        assertEquals(drift, PinsEvaluator.lint(pins).stream().map(Finding::ga).anyMatch(bom::equals));
    }

    @Test
    void lastRuleWins() throws Exception {
        Pins p = pins("""
                {"packageRules": [
                  {"matchPackageNames": ["a:b"], "allowedVersions": "/^1.0$/"},
                  {"matchPackageNames": ["a:b"], "allowedVersions": "/^2.0$/"}]}""");
        assertEquals("2.0", p.constraintFor("a:b").exact());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            unsupported condition | {"matchPackageNames": ["a:b"], "matchUpdateTypes": ["major"], "allowedVersions": "/^1.0$/"}
            exclude condition     | {"matchPackageNames": ["a:*"], "excludePackageNames": ["a:b"], "allowedVersions": "/^1.0$/"}
            unknown matcher       | {"matchPackageNames": ["a:b"], "matchDepPatterns": ["^a"], "allowedVersions": "/^1.0$/"}
            versioning            | {"matchPackageNames": ["a:b"], "versioning": "loose", "allowedVersions": "/^1.0$/"}
            invalid regex         | {"matchPackageNames": ["a:b"], "allowedVersions": "/^(1.0$/"}
            unsupported flag      | {"matchPackageNames": ["a:b"], "allowedVersions": "/^1\\\\.0/s"}
            unsupported name flag | {"matchPackageNames": ["a:b", "/^a:/m"], "allowedVersions": "/^1\\\\.0$/"}
            semver range          | {"matchPackageNames": ["a:b"], "allowedVersions": "<2.0"}""")
    void skipped(String name, String rule) throws Exception {
        Pins p = pins("{\"packageRules\": [" + rule + "]}");
        assertNull(p.constraintFor("a:b"));
        assertEquals(1, p.skipped().size(), p.skipped().toString());
    }

    @Test
    void keysThatDoNotChangeAllowedVersionsKeepThePin() throws Exception {
        Pins p = pins("""
                {"packageRules": [{"description": "d", "matchManagers": ["maven"], "matchDatasources": ["maven"],
                  "matchPackageNames": ["a:b"], "allowedVersions": "/^1.0$/", "enabled": true, "groupName": "g",
                  "groupSlug": "g", "addLabels": ["l"], "labels": ["l"], "prBodyNotes": ["n"],
                  "registryUrls": ["https://repo"], "separateMinorPatch": true},
                  {"matchManagers": ["gomod"], "matchUpdateTypes": ["major"], "allowedVersions": "/^1.0$/",
                  "matchPackageNames": ["a:c"]}]}""");
        assertEquals("1.0", p.constraintFor("a:b").exact());
        // not a Maven rule: neither a pin nor skipped
        assertEquals(List.of(), p.skipped());
    }

    @ParameterizedTest(name = "{0} matches {1}: {2}")
    @CsvSource({
            "org.example:a?c,  org.example:abc,  true",
            "org.example:a?c,  org.example:a/c,  false",
            "/^org\\.EXAMPLE:/i, org.example:abc,  true"})
    void packageName(String pattern, String ga, boolean pinned) throws Exception {
        Pins p = pins("""
                {"packageRules": [{"matchPackageNames": ["x:y", %s], "allowedVersions": "/^1.0$/"}]}"""
                .formatted(new ObjectMapper().writeValueAsString(pattern)));
        assertEquals(pinned, p.constraintFor(ga) != null);
    }

    @ParameterizedTest(name = "{1} above {0}: {2}")
    @CsvSource(delimiter = ';', textBlock = """
            /^2\\.28\\./;     2.29.0;     true
            /^2\\.28\\./;     2.27.5;     false
            /^2\\.28/;        2.29.0;     false
            !/^3\\.6\\.0$/;   3.6.0;      false
            /^5\\.30\\.0$/;   5.31.0;     false""")
    void above(String allowedVersions, String version, boolean above) {
        assertEquals(above, Pins.Constraint.parse(allowedVersions).above(version));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            no rules            | {}
            only a semver range | {"packageRules": [{"matchPackageNames": ["a:b"], "allowedVersions": "<=5.30.0"}]}""")
    void noPinsIsAnError(String name, String config, @TempDir Path tmp) throws Exception {
        LintMojo mojo = new LintMojo();
        mojo.pins = Files.writeString(tmp.resolve("pins.json"), config).toString();
        MojoExecutionException e = assertThrows(MojoExecutionException.class, mojo::loadPins);
        assertTrue(e.getMessage().startsWith("No pin rules in "), e.getMessage());
    }

    // ComparableVersion edges the gate relies on (feature-SNAPSHOT above release, trailing zeros equal)
    @ParameterizedTest(name = "{0} vs {1}: {2}")
    @CsvSource({
            "1.1.6, 1.1.6-feat-consul-auth-mode-SNAPSHOT, lt",
            "1.1.6-feat-consul-auth-mode-SNAPSHOT, 1.1.7, lt",
            "1.0-SNAPSHOT, 1.0, lt",
            "2.21, 2.21.0, eq",
            "9.1.3.Final, 9.1.3, eq"})
    void compareVersions(String a, String b, String order) {
        int cmp = Pins.compareVersions(a, b);
        if (order.equals("eq")) {
            assertEquals(0, cmp);
            assertEquals(0, Pins.compareVersions(b, a));
        } else {
            assertTrue(cmp < 0, a + " < " + b);
            assertTrue(Pins.compareVersions(b, a) > 0, b + " > " + a);
        }
    }
}
