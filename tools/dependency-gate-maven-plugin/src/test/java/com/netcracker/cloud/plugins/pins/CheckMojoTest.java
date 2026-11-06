package com.netcracker.cloud.plugins.pins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.MojoDescriptorCreator;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptorBuilder;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckMojoTest {

    @Test
    void noExcludesFileMeansNothingIsExcluded() throws Exception {
        assertEquals(List.of(), CheckMojo.readExcludes(null));
    }

    @Test
    void excludesIgnoreBlankLinesAndMatchAnyPartOfThePath(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("excludes.txt"), """
                generated/

                /broken/
                """);
        List<Pattern> excludes = CheckMojo.readExcludes(file.toFile());
        assertEquals(2, excludes.size());
        assertTrue(CheckMojo.excluded("app/generated/pom.xml", excludes));
        assertTrue(CheckMojo.excluded("modules/broken/pom.xml", excludes));
        assertFalse(CheckMojo.excluded("app/lib/pom.xml", excludes));
    }

    @Test
    void invalidExcludeRegexFailsClosed(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("excludes.txt"), "(");
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> CheckMojo.readExcludes(file.toFile()));
        assertTrue(e.getMessage().startsWith("Cannot read the excludes from "), e.getMessage());
        assertTrue(e.getMessage().contains("Unclosed group"), e.getMessage());
    }

    @Test
    void unbuildableRootsFailClosed() throws Exception {
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> CheckMojo.requireBuilt(List.of("broken/pom.xml (Parent relative path)")));
        assertEquals("Cannot build broken/pom.xml (Parent relative path)", e.getMessage());
        CheckMojo.requireBuilt(List.of());
    }

    @Test
    void excludedModulesAreNotEvaluated() throws Exception {
        var projects = Fixtures.projects(Fixtures.REPO, new Properties());
        var collector = new UsageCollector(Fixtures.REPO, new Properties());
        var checked = CheckMojo.checkedProjects(projects, collector, List.of(Pattern.compile("quarkus-app/")));
        assertEquals(projects.size() - 1, checked.size());
        assertTrue(checked.stream().noneMatch(p -> p.getArtifactId().equals("fixture-quarkus-app")));
        var findings = Fixtures.byKey(Fixtures.check(collector.collect(projects, checked)));
        assertFalse(findings.containsKey("pin-exceeded|io.quarkus:quarkus-core|3.40.1"), findings.keySet().toString());
        assertTrue(findings.containsKey("pin-exceeded|com.rabbitmq:amqp-client|5.37.0"), findings.keySet().toString());
    }

    /**
     * The goal as Maven runs it: the generated descriptor of {@code check}, the pom's plugin configuration merged over
     * the descriptor defaults, the pom's properties and {@code -D}. No {@code expected} means the check runs.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            nothing in the pom                      |                                           |                                     | dependency-gate.pins=pins.json      |
            -D only, several parameters             |                                           |                                     | dependency-gate.pins=p.json;dependency-gate.transitive=true;dependency-gate.findings=f.json |
            configuration over -D                   | <pins>lax.json</pins>                     |                                     | dependency-gate.pins=pins.json      | dependency-gate.pins (configured as lax.json)
            configuration not given with -D         | <excludesFile>excludes.txt</excludesFile> |                                     |                                     | dependency-gate.excludesFile (configured as excludes.txt)
            configured list                         | <poms><pom>a</pom><pom>b</pom></poms>     |                                     |                                     | dependency-gate.poms (configured as [a,b])
            configured as its own expression        | <pins>${dependency-gate.pins}</pins>      |                                     | dependency-gate.pins=pins.json      |
            several at once                         | <skip>true</skip><revision>base</revision> |                                    |                                     | dependency-gate.skip (configured as true);dependency-gate.revision (configured as base)
            pom property not given with -D          |                                           | dependency-gate.root=elsewhere      |                                     | dependency-gate.root (set to elsewhere not with -D)
            pom property differs from -D            |                                           | dependency-gate.transitive=false    | dependency-gate.transitive=true     |
            maxLocations may be configured          | <maxLocations>1</maxLocations>            | dependency-gate.maxLocations=2      |                                     |""")
    void gateParametersComeOnlyFromTheCommandLine(String name, String configuration, String pomProperties,
                                                  String userProperties, String expected) throws Exception {
        assertGuard(mojo(configuration, pomProperties, userProperties, null), expected);
    }

    /**
     * Maven reads {@code .mvn/maven.config} into the user properties, as if given with {@code -D}. Its gate
     * parameters are rejected even when the command line passes them too and wins.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            -D with a value      | -Ddependency-gate.excludesFile=ex.txt                    | dependency-gate.excludesFile (set in .mvn/maven.config)
            -D and the property  | -D;dependency-gate.poms=tiny                             | dependency-gate.poms (set in .mvn/maven.config)
            --define             | --define=dependency-gate.skip=true                       | dependency-gate.skip (set in .mvn/maven.config)
            several              | -Ddependency-gate.pins=lax.json;-Ddependency-gate.root=x | dependency-gate.pins (set in .mvn/maven.config);dependency-gate.root (set in .mvn/maven.config)
            commented out        | # -Ddependency-gate.skip=true                            |
            maxLocations         | -Ddependency-gate.maxLocations=1                         |
            other options        | -T;4;-Dskip.tests=true                                   |""")
    void mavenConfigCannotSetGateParameters(String name, String mavenConfig, String expected, @TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve(".mvn"));
        Files.writeString(dir.resolve(".mvn").resolve("maven.config"), String.join("\n", mavenConfig.split(";")));
        // what the gate workflow passes
        String commandLine = "dependency-gate.pins=pins.json;dependency-gate.excludesFile=excludes.txt;"
                + "dependency-gate.revision=head;dependency-gate.root=.;dependency-gate.skip=false;"
                + "dependency-gate.transitive=true;dependency-gate.poms=.;dependency-gate.findings=f.json";
        assertGuard(mojo(null, null, commandLine, dir), expected);
    }

    /**
     * A failed check still leaves the error where the gate looks for it, as the revision the gate asked for: both come
     * from {@code -D} only, not from the pom that the check rejects.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            invalid revision    |                                             | dependency-gate.revision=nope;dependency-gate.findings=f.json | f.json                                 | head | revision must be one of [head, base], not nope
            revision in the pom | <revision>base</revision>                   | dependency-gate.findings=f.json                               | f.json                                 | head | dependency-gate.revision (configured as base)
            findings in the pom | <findingsFile>elsewhere.json</findingsFile> | dependency-gate.revision=base                                 | target/dependency-gate/maven-pins.json | base | dependency-gate.findings (configured as elsewhere.json)""")
    void errorFileGoesWhereTheGateLooks(String name, String configuration, String userProperties, String file,
                                        String revision, String reason, @TempDir Path dir) throws Exception {
        CheckMojo mojo = mojo(configuration, null, userProperties, dir);
        mojo.plugin = mojo.mojoExecution.getMojoDescriptor().getPluginDescriptor();
        MojoExecutionException e = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(e.getMessage().contains(reason), e.getMessage());
        JsonNode written = new ObjectMapper().readTree(dir.resolve(file).toFile());
        assertEquals("error", written.path("adapter").path("status").asText());
        assertEquals(revision, written.path("revision").asText());
        assertEquals(e.getMessage(), written.path("adapter").path("reason").asText());
        assertFalse(Files.exists(dir.resolve("elsewhere.json")));
    }

    /**
     * @param expected the rejected properties separated by {@code ;}, or null if the check runs
     */
    private static void assertGuard(CheckMojo mojo, String expected) throws Exception {
        if (expected == null) {
            mojo.checkParameters();
            return;
        }
        MojoExecutionException e = assertThrows(MojoExecutionException.class, mojo::checkParameters);
        assertTrue(e.getMessage().startsWith("Parameters of the dependency gate are set in the checked project: "),
                e.getMessage());
        List<String> parts = List.of(expected.split(";"));
        parts.forEach(part -> assertTrue(e.getMessage().contains(part), e.getMessage()));
        assertEquals(parts.size(), e.getMessage().split(" \\((configured as|set to|set in) ").length - 1,
                e.getMessage());
    }

    /**
     * @param directory the execution root and the directory of {@code .mvn}; null for none
     */
    private static CheckMojo mojo(String configuration, String pomProperties, String userProperties, Path directory)
            throws Exception {
        CheckMojo mojo = new CheckMojo(null, null);
        mojo.session = session(properties(pomProperties), properties(userProperties), directory);
        mojo.mojoExecution = execution(Objects.toString(configuration, ""));
        return mojo;
    }

    private static MojoExecution execution(String configuration) throws Exception {
        PluginDescriptor plugin;
        try (Reader descriptor = new InputStreamReader(
                CheckMojoTest.class.getResourceAsStream("/META-INF/maven/plugin.xml"), StandardCharsets.UTF_8)) {
            plugin = new PluginDescriptorBuilder().build(descriptor);
        }
        MojoDescriptor check = plugin.getMojo("check");
        // what Maven runs: the configuration of the pom, completed with the descriptor's expressions and defaults
        Xpp3Dom pom = Xpp3DomBuilder.build(new StringReader("<configuration>" + configuration + "</configuration>"));
        return new MojoExecution(check, Xpp3Dom.mergeXpp3Dom(pom, MojoDescriptorCreator.convert(check)));
    }

    private static MavenSession session(Properties pomProperties, Properties userProperties, Path directory) {
        Model model = new Model();
        model.setProperties(pomProperties);
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setUserProperties(userProperties);
        if (directory != null) {
            request.setBaseDirectory(directory.toFile());
            request.setMultiModuleProjectDirectory(directory.toFile());
        }
        @SuppressWarnings("deprecation")
        MavenSession session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        session.setCurrentProject(new MavenProject(model));
        return session;
    }

    private static Properties properties(String pairs) {
        Properties properties = new Properties();
        for (String pair : Objects.toString(pairs, "").split(";")) {
            if (!pair.isBlank()) {
                String[] kv = pair.split("=", 2);
                properties.setProperty(kv[0].trim(), kv[1].trim());
            }
        }
        return properties;
    }
}
