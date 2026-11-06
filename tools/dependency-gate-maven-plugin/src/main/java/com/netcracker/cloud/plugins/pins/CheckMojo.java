package com.netcracker.cloud.plugins.pins;

import com.netcracker.cloud.plugins.pins.PinsEvaluator.Finding;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Finds dependency versions that go past the Renovate pins of qubership-core-infra and writes them as findings.v1
 * of the dependency gate. A finding never fails the build: levels, exceptions and the baseline are applied by the
 * gate. The build fails only when the check itself cannot finish; the findings file then says why.
 *
 * <p>Runs once for the whole reactor (or for the projects given in {@code poms}) and checks the versions the
 * repository declares as well as the effective ones: dependencies, dependency management with imported BOMs, build
 * plugins, plugin management, plugin dependencies, annotation processor paths and extensions. Optionally also the
 * resolved dependency tree.</p>
 */
@Mojo(name = "check", aggregator = true, requiresProject = false, threadSafe = true,
        defaultPhase = LifecyclePhase.VALIDATE)
public class CheckMojo extends AbstractPinsMojo {

    /**
     * Where to write the findings (findings.v1). Default: {@code dependency-gate/maven-pins.json} in the build
     * directory of the project, or in {@code target} of the current directory when there is no pom.
     */
    // read from the user properties (findingsPath), as the pom may set it
    @Parameter(property = "dependency-gate.findings")
    File findingsFile;

    /**
     * {@code head}: the change under review; {@code base}: its base, which the gate uses as the baseline.
     */
    // read from the user properties (execute), as the pom may set it
    @Parameter(property = "dependency-gate.revision", defaultValue = "head")
    String revision;

    /**
     * File with Java regular expressions of repository-relative pom paths not to check, one per line; blank lines
     * are ignored. A pom is not checked when an expression matches any part of its path.
     */
    @Parameter(property = "dependency-gate.excludesFile")
    File excludesFile;

    /**
     * Root poms (or their directories) to check instead of the current reactor, with their modules.
     */
    @Parameter(property = "dependency-gate.poms")
    List<File> poms;

    /**
     * Repository root that finding locations are relative to. Default: the closest directory with {@code .git} above
     * the execution root, or the execution root.
     */
    @Parameter(property = "dependency-gate.root")
    File rootDirectory;

    /**
     * Also check the resolved dependency tree, transitive dependencies included. What only the tree shows is marked
     * {@code transitive}.
     */
    @Parameter(property = "dependency-gate.transitive", defaultValue = "false")
    boolean transitive;

    /**
     * Locations printed per finding in the build log.
     */
    @Parameter(property = "dependency-gate.maxLocations", defaultValue = "5")
    int maxLocations;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    PluginDescriptor plugin;

    private final ProjectBuilder projectBuilder;
    private final RepositorySystem repositorySystem;

    /**
     * @param incomplete the projects whose dependency tree is incomplete, with the reason
     */
    private record Result(List<Finding> findings, List<String> incomplete) {
    }

    @Inject
    public CheckMojo(ProjectBuilder projectBuilder, RepositorySystem repositorySystem) {
        this.projectBuilder = projectBuilder;
        this.repositorySystem = repositorySystem;
    }

    @Override
    public void execute() throws MojoExecutionException {
        // the file goes where the gate expects it even when the pom sets these parameters and the check fails
        String requested = session.getUserProperties().getProperty("dependency-gate.revision", "head");
        String fileRevision = FindingsFile.REVISIONS.contains(requested) ? requested : "head";
        String tool = plugin.getArtifactId() + " " + plugin.getVersion();
        Path output = findingsPath();
        Result result;
        try {
            checkParameters();
            if (!fileRevision.equals(requested)) {
                throw new MojoExecutionException("revision must be one of " + FindingsFile.REVISIONS + ", not "
                        + requested);
            }
            if (skip) {
                getLog().info("Skipping the dependency pins check");
                return;
            }
            result = check();
        } catch (MojoExecutionException | RuntimeException e) {
            write(FindingsFile.error(tool, fileRevision, e.getMessage()), output);
            throw e;
        }
        log(result.findings(), maxLocations, getLog()::info);
        String reason = result.incomplete().isEmpty() ? null
                : "incomplete dependency tree, transitive findings may be missing: "
                + String.join("; ", result.incomplete());
        write(FindingsFile.ok(tool, fileRevision, result.findings(), reason), output);
        getLog().info(result.findings().size() + " finding(s) written to " + output
                + "; the dependency gate decides what they mean");
    }

    private Result check() throws MojoExecutionException {
        UsageCollector collector = new UsageCollector(root(), session.getUserProperties());
        List<Pattern> excludes = readExcludes(excludesFile);
        Pins rules = loadPins();
        List<MavenProject> projects = projects();
        List<MavenProject> checked = checkedProjects(projects, collector, excludes);

        collector.collect(projects, checked);
        List<String> incomplete = new ArrayList<>();
        if (transitive) {
            for (MavenProject p : checked) {
                String problem = resolved(p, collector);
                if (problem != null) {
                    incomplete.add(p.getGroupId() + ":" + p.getArtifactId() + ": " + problem);
                }
            }
        }
        getLog().info("Checking " + checked.size() + " project(s), " + collector.usages().size() + " version usage(s)");
        return new Result(new PinsEvaluator(rules).managedBy(this::managedBy).evaluate(collector.usages(),
                collector.own()), incomplete);
    }

    /**
     * {@code findings} given with {@code -D}, relative to the base directory as Maven resolves a file parameter. The
     * project's build directory is not interpolated without a pom, so the default is resolved here.
     */
    private Path findingsPath() {
        String findings = session.getUserProperties().getProperty("dependency-gate.findings");
        if (findings != null) {
            return new PluginParameterExpressionEvaluator(session, mojoExecution)
                    .alignToBaseDirectory(new File(findings)).toPath();
        }
        MavenProject project = session.getCurrentProject();
        Path target = project != null && project.getFile() != null ? Path.of(project.getBuild().getDirectory())
                : Path.of(session.getExecutionRootDirectory(), "target");
        return target.resolve("dependency-gate").resolve("maven-pins.json");
    }

    private void write(FindingsFile file, Path output) throws MojoExecutionException {
        try {
            file.write(output);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write the findings to " + output, e);
        }
    }

    private Path root() {
        if (rootDirectory != null) {
            return rootDirectory.toPath().toAbsolutePath().normalize();
        }
        Path start = Path.of(session.getExecutionRootDirectory()).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
        }
        return start;
    }

    private ProjectBuildingRequest buildingRequest() {
        return new DefaultProjectBuildingRequest(session.getProjectBuildingRequest())
                .setResolveDependencies(false)
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
    }

    private List<MavenProject> projects() throws MojoExecutionException {
        List<File> files = poms == null ? List.of()
                : poms.stream().map(f -> f.isDirectory() ? new File(f, "pom.xml") : f).toList();
        if (files.isEmpty() || isWholeReactor(files)) {
            List<MavenProject> reactor = session.getProjects();
            if (reactor.isEmpty() || reactor.getFirst().getFile() == null) {
                throw new MojoExecutionException("No pom.xml to check: run in a Maven project or pass "
                        + "-Ddependency-gate.poms=<pom or directory>[,...]");
            }
            return reactor;
        }
        List<ProjectBuildingResult> results;
        try {
            results = projectBuilder.build(files, true, buildingRequest());
        } catch (ProjectBuildingException e) {
            if (e.getResults() == null) {
                throw new MojoExecutionException("Cannot build " + files + ": " + e.getMessage(), e);
            }
            results = e.getResults();
        }
        // a pom that could not be built is not checked, so it must not pass as clean
        List<String> failed = new ArrayList<>();
        // a root and one of its modules may both be given: keep each pom once
        Map<Path, MavenProject> projects = new LinkedHashMap<>();
        for (ProjectBuildingResult result : results) {
            String error = result.getProblems().stream()
                    .filter(problem -> problem.getSeverity() != ModelProblem.Severity.WARNING)
                    .map(ModelProblem::getMessage).findFirst()
                    .orElse(result.getProject() == null ? "not built" : null);
            if (error != null) {
                failed.add(result.getPomFile() + " (" + error + ")");
            } else {
                projects.putIfAbsent(result.getProject().getFile().toPath().toAbsolutePath().normalize(),
                        result.getProject());
            }
        }
        requireBuilt(failed);
        return List.copyOf(projects.values());
    }

    /**
     * {@code poms} names the root of the whole current reactor (such as {@code .}): Maven has already built it, with
     * its modules resolved against each other.
     */
    private boolean isWholeReactor(List<File> files) {
        MavenProject top = session.getTopLevelProject();
        List<MavenProject> all = session.getAllProjects();
        return files.size() == 1 && top != null && top.getFile() != null
                && all != null && session.getProjects().size() == all.size()
                && files.getFirst().toPath().toAbsolutePath().normalize()
                .equals(top.getFile().toPath().toAbsolutePath().normalize());
    }

    /**
     * Java regexes of repository-relative pom paths, one per line; blank lines are ignored.
     */
    static List<Pattern> readExcludes(File excludesFile) throws MojoExecutionException {
        if (excludesFile == null) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(excludesFile.toPath())) {
            return lines.filter(s -> !s.isBlank()).map(Pattern::compile).toList();
        } catch (IOException | UncheckedIOException | PatternSyntaxException e) {
            throw new MojoExecutionException("Cannot read the excludes from " + excludesFile + ": " + e.getMessage(),
                    e);
        }
    }

    static boolean excluded(String relativePomPath, List<Pattern> excludes) {
        return excludes.stream().anyMatch(rx -> rx.matcher(relativePomPath).find());
    }

    static List<MavenProject> checkedProjects(List<MavenProject> projects, UsageCollector collector,
                                              List<Pattern> excludes) {
        return projects.stream().filter(p -> !excluded(collector.relative(p.getFile().toPath()), excludes)).toList();
    }

    /**
     * A root that could not be built must not pass as clean: the check fails closed with every failed path.
     */
    static void requireBuilt(List<String> failed) throws MojoExecutionException {
        if (!failed.isEmpty()) {
            throw new MojoExecutionException("Cannot build " + String.join(", ", failed));
        }
    }

    /**
     * The poms the managed versions of an external BOM or parent come from, see {@link PinsEvaluator#managedBy}.
     */
    private Collection<String> managedBy(String gav) {
        try {
            org.apache.maven.artifact.Artifact pom = RepositoryUtils.toArtifact(new DefaultArtifact(gav));
            return UsageCollector.managedSources(
                    projectBuilder.build(pom, buildingRequest().setProcessPlugins(false)).getProject().getModel());
        } catch (ProjectBuildingException | IllegalArgumentException e) {
            getLog().debug("Cannot build " + gav + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * The dependency tree as Maven resolves it (nearest wins). Only poms are read, nothing is built.
     *
     * @return why the tree is incomplete, null if it is complete
     */
    private String resolved(MavenProject p, UsageCollector collector) {
        ArtifactTypeRegistry types = session.getRepositorySession().getArtifactTypeRegistry();
        CollectRequest request = new CollectRequest();
        request.setRootArtifact(RepositoryUtils.toArtifact(p.getArtifact()));
        request.setRepositories(p.getRemoteProjectRepositories());
        p.getDependencies().forEach(d -> request.addDependency(RepositoryUtils.toDependency(d, types)));
        if (p.getDependencyManagement() != null) {
            p.getDependencyManagement().getDependencies()
                    .forEach(d -> request.addManagedDependency(RepositoryUtils.toDependency(d, types)));
        }
        CollectResult result;
        String problem = null;
        try {
            result = repositorySystem.collectDependencies(session.getRepositorySession(), request);
        } catch (DependencyCollectionException e) {
            getLog().warn("Dependency tree of " + p.getId() + " is incomplete: " + e.getMessage());
            result = e.getResult();
            problem = e.getMessage();
        }
        if (result != null && result.getRoot() != null) {
            String where = "dependency tree of " + p.getGroupId() + ":" + p.getArtifactId();
            walk(result.getRoot(), where, collector.relative(p.getFile().toPath()), collector);
        }
        return problem;
    }

    private static void walk(DependencyNode node, String where, String module, UsageCollector collector) {
        for (DependencyNode child : node.getChildren()) {
            Artifact a = child.getArtifact();
            if (a != null) {
                String scope = child.getDependency() != null ? child.getDependency().getScope() : "";
                collector.add(Usage.of(a.getGroupId() + ":" + a.getArtifactId(), a.getBaseVersion(),
                        Usage.Kind.RESOLVED, Usage.Source.RESOLVED, where + " (" + scope + ")", 0, module));
            }
            walk(child, where, module, collector);
        }
    }
}
