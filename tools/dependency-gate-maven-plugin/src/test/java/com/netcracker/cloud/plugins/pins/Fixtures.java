package com.netcracker.cloud.plugins.pins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netcracker.cloud.plugins.pins.PinsEvaluator.Finding;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.project.MavenProject;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds fixture repositories the way Maven does (inheritance, interpolation, BOM imports, location tracking), with
 * external poms taken from {@code fixtures/remote/<groupId>/<artifactId>/<version>/pom.xml} instead of a repository.
 */
final class Fixtures {

    static final Path ROOT = Path.of("src", "test", "resources", "fixtures").toAbsolutePath();
    static final Path REPO = ROOT.resolve("repo");
    static final Path PINS = ROOT.resolve("pins.json");

    private Fixtures() {
    }

    static Pins pins() throws Exception {
        return new Pins(new ObjectMapper().readTree(PINS.toFile()));
    }

    /**
     * The root pom and its modules, recursively.
     */
    static List<MavenProject> projects(Path root, Properties userProperties) throws Exception {
        List<Path> poms = new ArrayList<>();
        modules(root.resolve("pom.xml"), poms);
        ModelBuilder builder = new DefaultModelBuilderFactory().newInstance();
        Map<String, MavenProject> byGa = new LinkedHashMap<>();
        for (Path pom : poms) {
            ModelBuildingResult result = builder.build(request().setPomFile(pom.toFile())
                    .setUserProperties(userProperties));
            MavenProject project = new MavenProject(result.getEffectiveModel());
            project.setOriginalModel(result.getRawModel());
            project.setActiveProfiles(result.getActivePomProfiles(result.getModelIds().get(0)));
            project.setFile(pom.toFile());
            byGa.put(project.getGroupId() + ":" + project.getArtifactId(), project);
        }
        for (MavenProject project : byGa.values()) {
            Parent parent = project.getOriginalModel().getParent();
            if (parent != null) {
                project.setParent(byGa.get(parent.getGroupId() + ":" + parent.getArtifactId()));
            }
        }
        return new ArrayList<>(byGa.values());
    }

    private static void modules(Path pom, List<Path> out) throws Exception {
        out.add(pom);
        Model model;
        try (Reader reader = Files.newBufferedReader(pom)) {
            model = new MavenXpp3Reader().read(reader);
        }
        for (String module : model.getModules()) {
            modules(pom.getParent().resolve(module).resolve("pom.xml").normalize(), out);
        }
    }

    static UsageCollector collect(Path root) throws Exception {
        return collect(root, new Properties());
    }

    static UsageCollector collect(Path root, Properties userProperties) throws Exception {
        List<MavenProject> projects = projects(root, userProperties);
        return new UsageCollector(root, userProperties).collect(projects, projects);
    }

    /**
     * What the check mojo does after collecting usages.
     */
    static List<Finding> check(UsageCollector collector) throws Exception {
        return new PinsEvaluator(pins()).managedBy(Fixtures::managedBy).evaluate(collector.usages(), collector.own());
    }

    /**
     * What the check mojo does with the project builder: the sources of the managed versions of an external pom.
     */
    static Collection<String> managedBy(String gav) {
        String[] parts = gav.split(":");
        try {
            ModelSource source = new StubResolver().resolveModel(parts[0], parts[1], parts[2]);
            return UsageCollector.managedSources(new DefaultModelBuilderFactory().newInstance()
                    .build(request().setModelSource(source)).getEffectiveModel());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static ModelBuildingRequest request() {
        return new DefaultModelBuildingRequest()
                .setModelResolver(new StubResolver())
                .setLocationTracking(true)
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                .setSystemProperties(System.getProperties());
    }

    static Map<String, Finding> byKey(List<Finding> findings) {
        return findings.stream().collect(Collectors.toMap(Finding::key, Function.identity(), (a, b) -> a,
                LinkedHashMap::new));
    }

    static Map<String, Finding> check() throws Exception {
        return byKey(check(collect(REPO)));
    }

    private static final class StubResolver implements ModelResolver {

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version)
                throws UnresolvableModelException {
            Path pom = ROOT.resolve("remote").resolve(groupId).resolve(artifactId).resolve(version).resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                throw new UnresolvableModelException("no stub " + pom, groupId, artifactId, version);
            }
            return new FileModelSource(pom.toFile());
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(Repository repository) {
        }

        @Override
        public void addRepository(Repository repository, boolean replace) {
        }

        @Override
        public ModelResolver newCopy() {
            return this;
        }
    }
}
