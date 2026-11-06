package com.netcracker.cloud.plugins.pins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.plugins.pins.PinsEvaluator.Finding;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

abstract class AbstractPinsMojo extends AbstractMojo {

    static final String DEFAULT_PINS =
            "https://raw.githubusercontent.com/Netcracker/qubership-core-infra/main/renovate-core-config.json";

    private static final String PROPERTY_PREFIX = "dependency-gate.";

    private static final Pattern GATE_PROPERTY = Pattern.compile(Pattern.quote(PROPERTY_PREFIX) + "[A-Za-z]+");

    /**
     * renovate-core-config.json of qubership-core-infra: a URL or a file.
     */
    @Parameter(property = "dependency-gate.pins", defaultValue = DEFAULT_PINS)
    String pins;

    @Parameter(property = "dependency-gate.skip", defaultValue = "false")
    boolean skip;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    MojoExecution mojoExecution;

    /**
     * Parameters that only change the build log, so the pom may set them.
     */
    private static final Set<String> DISPLAY_PROPERTIES = Set.of(PROPERTY_PREFIX + "maxLocations");

    /**
     * The checked project is part of the change under review, so it must not relax its own check: every
     * {@code dependency-gate.*} parameter, except {@link #DISPLAY_PROPERTIES}, comes only from {@code -D} on the
     * command line, not from the plugin {@code <configuration>} (or its {@code default-cli} execution), the pom
     * {@code <properties>} or {@code .mvn/maven.config}, whose {@code -D} Maven turns into user properties too.
     */
    void checkParameters() throws MojoExecutionException {
        Properties user = session.getUserProperties();
        Set<String> mavenConfig = mavenConfigProperties(session.getRequest().getMultiModuleProjectDirectory());
        Xpp3Dom configuration = mojoExecution.getConfiguration();
        PluginParameterExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(session, mojoExecution);
        List<String> set = new ArrayList<>();
        for (var parameter : mojoExecution.getMojoDescriptor().getParameters()) {
            String expression = Objects.toString(parameter.getExpression(), "");
            String property = expression.startsWith("${" + PROPERTY_PREFIX) && expression.endsWith("}")
                    ? expression.substring(2, expression.length() - 1) : null;
            if (property == null || DISPLAY_PROPERTIES.contains(property)) {
                continue;
            }
            if (mavenConfig.contains(property)) {
                set.add(property + " (set in .mvn/maven.config)");
                continue;
            }
            // an unconfigured parameter keeps the expression of the mojo descriptor; a configured list keeps it too,
            // with the elements as children
            Xpp3Dom node = configuration == null ? null : configuration.getChild(parameter.getName());
            if (node != null && (node.getChildCount() > 0 || !expression.equals(node.getValue()))) {
                set.add(property + " (configured as " + describe(node) + ")");
                continue;
            }
            Object value;
            try {
                value = evaluator.evaluate(expression);
            } catch (ExpressionEvaluationException e) {
                value = null;
            }
            if (!Objects.equals(user.getProperty(property), Objects.toString(value, null))) {
                set.add(property + " (set to " + value + " not with -D)");
            }
        }
        if (!set.isEmpty()) {
            throw new MojoExecutionException("Parameters of the dependency gate are set in the checked project: "
                    + String.join(", ", set) + ". Pass them with -D on the command line only, not in the "
                    + "<configuration> of " + mojoExecution.getArtifactId() + ", <properties> or .mvn/maven.config");
        }
    }

    /**
     * The {@code dependency-gate.*} properties named in {@code .mvn/maven.config} (comment lines aside), in any
     * option form ({@code -Dname=value}, {@code -D name}, {@code --define=name}).
     */
    static Set<String> mavenConfigProperties(File multiModuleProjectDirectory) throws MojoExecutionException {
        Path config = multiModuleProjectDirectory == null ? null
                : multiModuleProjectDirectory.toPath().resolve(".mvn").resolve("maven.config");
        if (config == null || !Files.isRegularFile(config)) {
            return Set.of();
        }
        try (Stream<String> lines = Files.lines(config, StandardCharsets.ISO_8859_1)) {
            return lines.filter(line -> !line.strip().startsWith("#"))
                    .flatMap(line -> GATE_PROPERTY.matcher(line).results()).map(MatchResult::group)
                    .collect(Collectors.toSet());
        } catch (IOException | UncheckedIOException e) {
            throw new MojoExecutionException("Cannot read " + config + ": " + e.getMessage(), e);
        }
    }

    /** How a configured parameter is shown in the error when it comes from the pom rather than {@code -D}. */
    static String describe(Xpp3Dom node) {
        return node.getChildCount() == 0 ? node.getValue()
                : Arrays.stream(node.getChildren()).map(Xpp3Dom::getValue).collect(Collectors.joining(",", "[", "]"));
    }

    Pins loadPins() throws MojoExecutionException {
        JsonNode config;
        try (InputStream in = open(pins)) {
            config = new ObjectMapper().readTree(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("Cannot read the pins from " + pins + ": " + e.getMessage(), e);
        }
        Pins result = new Pins(config);
        result.skipped().forEach(s -> getLog().debug("Not a pin: " + s));
        getLog().info(result.size() + " pin rule(s) from " + pins + ", " + result.skipped().size()
                + " rule(s) skipped (listed in the debug log)");
        if (result.size() == 0) {
            // a wrong source or a new generator format must not pass as "nothing exceeds the pins"
            throw new MojoExecutionException("No pin rules in " + pins + ", " + result.skipped().size()
                    + " rule(s) skipped");
        }
        return result;
    }

    private InputStream open(String source) throws IOException {
        if (!source.matches("(?i)https?://.*")) {
            return Files.newInputStream(Path.of(source).toAbsolutePath());
        }
        URI uri = URI.create(source);
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL);
        // the proxy Maven itself would use for this URL (settings.xml, nonProxyHosts applied)
        Proxy proxy = session.getRepositorySession().getProxySelector()
                .getProxy(new RemoteRepository.Builder("dependency-gate-pins", "default", source).build());
        if (proxy != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "dependency-gate-maven-plugin").build();
        try (HttpClient client = builder.build()) {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return new ByteArrayInputStream(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while downloading " + source);
        }
    }

    static void log(List<Finding> findings, int maxLocations, Consumer<String> level) {
        findings.stream().sorted(Finding.ORDER).forEach(f -> level.accept(f.describe(maxLocations)));
    }
}
