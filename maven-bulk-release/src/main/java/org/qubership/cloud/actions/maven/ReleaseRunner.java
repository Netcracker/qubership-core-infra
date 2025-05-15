package org.qubership.cloud.actions.maven;

import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.nio.dot.DOTExporter;
import org.qubership.cloud.actions.maven.model.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class ReleaseRunner {
    static Pattern propertyPattern = Pattern.compile("\\$\\{(.*?)}");

    UsernamePasswordCredentialsProvider credentialsProvider;

    public ReleaseRunner(String gitUsername, String gitPassword) {
        credentialsProvider = new UsernamePasswordCredentialsProvider(gitUsername, gitPassword);
        CredentialsProvider.setDefault(credentialsProvider);
    }

    public Result release(Config config) {
        Result result = new Result();
        Map<GA, String> dependenciesGavs = config.getDependencies().stream().map(GAV::new).collect(Collectors.toMap(gav -> new GA(gav.getGroupId(), gav.getArtifactId()), GAV::getVersion));
        // build dependency graph
        Map<Integer, List<RepositoryInfo>> dependencyGraph = buildDependencyGraph(config);
        String dot = generateDotFile(dependencyGraph);
        result.setDependenciesDot(dot);

        List<Release> allReleases = dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey();
            log.info("Processing level {}/{}, repositories:\n{}", level + 1, dependencyGraph.size(), String.join("\n", entry.getValue().stream().map(Repository::getUrl).toList()));
            List<RepositoryInfo> reposInfoList = entry.getValue();
            int threads = reposInfoList.size();
//            int threads = 1;
            try (ExecutorService executorService = Executors.newFixedThreadPool(threads)) {
                Set<GAV> gavList = dependenciesGavs.entrySet().stream().map(e -> new GAV(e.getKey().getGroupId(), e.getKey().getArtifactId(), e.getValue())).collect(Collectors.toSet());
                List<Release> releases = reposInfoList.stream()
                        .map(repo -> executorService.submit(() -> release(config, repo, gavList)))
                        .toList()
                        .stream()
                        .map(future -> {
                            try {
                                return future.get();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            } catch (ExecutionException e) {
                                throw new RuntimeException(e);
                            }
                        }).toList();
                releases.stream().flatMap(r -> r.getGavs().stream())
                        .forEach(gav -> dependenciesGavs.put(new GA(gav.getGroupId(), gav.getArtifactId()), gav.getVersion()));
                return releases.stream();
            }
        }).toList();

        if (config.isRunDeploy()) {
            try (ExecutorService executorService = Executors.newFixedThreadPool(4)) {
                allReleases.stream()
                        .map(release -> executorService.submit(() -> performRelease(config, release)))
                        .toList()
                        .forEach(future -> {
                            try {
                                future.get();
                            } catch (Exception e) {
                                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
        result.setReleases(allReleases);
        return result;
    }

    Map<Integer, List<RepositoryInfo>> buildDependencyGraph(Config config) {
        String baseDir = config.getBaseDir();
        List<String> repositories = config.getRepositories();
        Predicate<GA> dependenciesFilter = config.getDependenciesFilter();
        try (ExecutorService executorService = Executors.newFixedThreadPool(5)) {
            List<RepositoryInfo> repositoryInfoList = repositories.stream().map(RepositoryInfo::new)
                    .map(repositoryInfo -> executorService.submit(() -> {
                        gitCheckout(baseDir, repositoryInfo);
                        List<PomHolder> poms = getPoms(baseDir, repositoryInfo);
                        resolveDependencies(repositoryInfo, poms, dependenciesFilter);
                        return repositoryInfo;
                    })).toList()
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        } catch (ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                    }).toList();
            for (RepositoryInfo repositoryInfo : repositoryInfoList.stream().filter(ri -> !ri.getModuleDependencies().isEmpty()).toList()) {
                Set<GA> moduleDependencies = repositoryInfo.getModuleDependencies().stream()
                        .map(gav -> new GA(gav.getGroupId(), gav.getArtifactId())).collect(Collectors.toSet());
                repositoryInfo.getRepoDependencies().addAll(moduleDependencies.stream()
                        .flatMap(ga -> repositoryInfoList.stream().filter(ri -> ri.getModules().contains(ga)))
                        .filter(repo -> !Objects.equals(repo.getUrl(), repositoryInfo.getUrl()))
                        .collect(Collectors.toSet()));
            }
            Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);

            for (RepositoryInfo repositoryInfo : repositoryInfoList) {
                graph.addVertex(repositoryInfo.getUrl());
            }
            for (RepositoryInfo repositoryInfo : repositoryInfoList) {
                repositoryInfo.getRepoDependenciesFlatSet().forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
            }

            List<RepositoryInfo> independentRepos = repositoryInfoList.stream().filter(ri -> graph.incomingEdgesOf(ri.getUrl()).isEmpty()).toList();
            List<RepositoryInfo> dependentRepos = repositoryInfoList.stream().filter(ri -> !graph.incomingEdgesOf(ri.getUrl()).isEmpty()).collect(Collectors.toList());
            Map<Integer, List<RepositoryInfo>> groupedReposMap = new TreeMap<>();
            groupedReposMap.put(0, independentRepos);
            int level = 1;
            while (!dependentRepos.isEmpty()) {
                List<RepositoryInfo> prevLevelRepos = IntStream.range(0, level).boxed().flatMap(lvl -> groupedReposMap.get(lvl).stream()).toList();
                List<RepositoryInfo> thisLevelRepos = dependentRepos.stream()
                        .filter(ri -> graph.incomingEdgesOf(ri.getUrl()).stream().map(StringEdge::getSource)
                                .allMatch(dependentRepoUrl -> prevLevelRepos.stream().map(RepositoryInfo::getUrl).collect(Collectors.toSet()).contains(dependentRepoUrl))).toList();
                groupedReposMap.put(level, thisLevelRepos);
                dependentRepos.removeAll(thisLevelRepos);
                level++;
            }
            return groupedReposMap;
        }
    }

    void gitCheckout(String baseDir, Repository repository) {
        Path repositoryDirPath = Paths.get(baseDir, repository.getDir());
        boolean repositoryDirExists = Files.exists(repositoryDirPath);
        try {
            if (!repositoryDirExists) {
                Files.createDirectories(repositoryDirPath);
            }
            try (Stream<Path> pathStream = Files.walk(repositoryDirPath)) {
                pathStream.sorted(Comparator.comparingInt(p -> p.toString()
                                .replaceAll("[^/.]", "")
                                .replace(".", "/").length()).reversed())
                        .forEach(file -> {
                            try {
                                Files.delete(file);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
            try (Git git = Git.cloneRepository()
                    .setCredentialsProvider(credentialsProvider)
                    .setURI(repository.getUrl())
                    .setDirectory(repositoryDirPath.toFile())
                    .setDepth(1)
                    .setBranch("HEAD")
                    .setCloneAllBranches(false)
                    .setTagOption(TagOpt.FETCH_TAGS)
                    .setProgressMonitor(new TextProgressMonitor(new PrintWriter(new OutputStreamWriter(System.out, UTF_8))))
                    .call()) {
//                git.fetch().call();
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Model getEffectivePom(Path path, String artifact) {
        try {
            Files.deleteIfExists(Path.of(path.toString(), "effective-pom.xml"));
            List<String> cmd = List.of("mvn", "help:effective-pom", "-Dartifact=" + artifact, "-Doutput=effective-pom.xml");
            log.info("pom file: {}\nCmd: '{}' started", path, String.join(" ", cmd));
            Process process = new ProcessBuilder(cmd).directory(path.toFile()).start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            process.getInputStream().transferTo(baos);
            process.getErrorStream().transferTo(baos);
            process.waitFor();
            log.info("pom file: {}\nCmd: '{}' ended with code: {}", path, String.join(" ", cmd), process.exitValue());
            if (process.exitValue() != 0) {
                throw new RuntimeException(String.format("Failed to execute cmd, error: %s", baos));
            }
            String effectivePomContent = Files.readString(Path.of(path.toString(), "effective-pom.xml"));
            return new MavenXpp3Reader().read(new ByteArrayInputStream(effectivePomContent.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<PomHolder> getPoms(String baseDir, RepositoryInfo repositoryInfo) {
        Path repositoryDirPath = Paths.get(baseDir, repositoryInfo.getDir());
        List<PomHolder> poms = new ArrayList<>();
        try {
            Files.walkFileTree(repositoryDirPath, new FileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Arrays.asList(file.toString().split("/")).contains("pom.xml")) {
                        PomHolder pomHolder = new PomHolder();
                        String content = Files.readString(file);
                        pomHolder.setPath(file);
                        pomHolder.setPom(content);
                        try {
                            MavenXpp3Reader mavenReader = new MavenXpp3Reader();
                            Model model = mavenReader.read(new ByteArrayInputStream(content.getBytes()));
                            pomHolder.setModel(model);
                        } catch (XmlPullParserException e) {
                            throw new RuntimeException(e);
                        }
                        poms.add(pomHolder);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return poms.stream()
                .peek(pom -> {
                    Parent parent = pom.getModel().getParent();
                    if (parent != null) {
                        String groupId = parent.getGroupId();
                        String artifactId = parent.getArtifactId();
                        poms.stream().filter(ph -> Objects.equals(ph.getGroupId(), groupId) && Objects.equals(ph.getArtifactId(), artifactId))
                                .findFirst().ifPresent(pom::setParent);
                    }
                })
                // start with leaf poms
                .sorted(Comparator.<PomHolder>comparingInt(p -> p.getParentsFlatList().size()).reversed())
                .toList();
    }

    void resolveDependencies(RepositoryInfo repositoryInfo, List<PomHolder> poms, Predicate<GA> dependenciesFilter) {
        repositoryInfo.getModules().clear();
        repositoryInfo.getModuleDependencies().clear();
        try {
            for (PomHolder pomHolder : poms) {
                Map<String, String> properties = new HashMap<>();
                Model project = pomHolder.getModel();
                GA projectGA = pomGAFunction.apply(project);
                String projectGroupId = autoResolveReferencedValue(pomHolder, projectGA.getGroupId(), properties);
                String projectArtifactId = autoResolveReferencedValue(pomHolder, projectGA.getArtifactId(), properties);
                GA moduleGA = new GA(projectGroupId, projectArtifactId);
                repositoryInfo.getModules().add(moduleGA);
            }
            for (PomHolder pomHolder : poms) {
                Model project = pomHolder.getModel();
                Map<String, String> properties = new HashMap<>();
                List<Dependency> dependencyManagementNodes = Optional.ofNullable(project.getDependencyManagement())
                        .map(DependencyManagement::getDependencies)
                        .orElse(List.of());
                List<Dependency> dependenciesNodes = Optional.ofNullable(project.getDependencies()).orElse(List.of());
                List<Dependency> allDependenciesNodes = Stream.concat(dependencyManagementNodes.stream(), dependenciesNodes.stream()).toList();
                for (Dependency dependency : allDependenciesNodes) {
                    String groupId = autoResolveReferencedValue(pomHolder, dependency.getGroupId(), properties);
                    String artifactId = autoResolveReferencedValue(pomHolder, dependency.getArtifactId(), properties);
                    String version = autoResolveReferencedValue(pomHolder, dependency.getVersion(), properties);
                    if (Stream.of(groupId, artifactId, version).allMatch(Objects::nonNull)) {
                        GAV dependencyGAV = new GAV(groupId, artifactId, version);
                        GA dependencyGA = new GA(groupId, artifactId);
                        if (dependenciesFilter.test(dependencyGA) && !repositoryInfo.getModules().contains(dependencyGA)) {
                            repositoryInfo.getModuleDependencies().add(dependencyGAV);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Release release(Config config, RepositoryInfo repository, Collection<GAV> dependencies) {
        String baseDir = config.getBaseDir();
        List<PomHolder> poms = getPoms(baseDir, repository);
        updateDependencies(baseDir, repository, poms, dependencies);
        String releaseVersion = calculateReleaseVersion(repository, poms, config.getVersionIncrementType());
        String javaVersion = calculateJavaVersion(poms);
        return releasePrepare(repository, config, releaseVersion, javaVersion);
    }

    String calculateReleaseVersion(RepositoryInfo repository, List<PomHolder> poms, VersionIncrementType versionIncrementType) {
        Set<String> pomVersions = poms.stream().map(PomHolder::getVersion).collect(Collectors.toSet());
        if (pomVersions.size() != 1) {
            throw new IllegalArgumentException(String.format("pom.xml files from repository: %s have different versions: %s",
                    repository.getUrl(), String.join(",", pomVersions)));
        }
        String pomVersion = pomVersions.iterator().next();
        Pattern semverPattern = Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<patch>\\d+)(?<snapshot>-SNAPSHOT)?");
        Matcher matcher = semverPattern.matcher(pomVersion);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Non-semver version: %s. Must match pattern: '%s'", pomVersion, semverPattern.pattern()));
        }
        int major = Integer.parseInt(matcher.group("major"));
        int minor = Integer.parseInt(matcher.group("minor"));
        int patch = Integer.parseInt(matcher.group("patch"));
        switch (versionIncrementType) {
            case MAJOR -> {
                major++;
                minor = 0;
                patch = 0;
            }
            case MINOR -> {
                minor++;
                patch = 0;
            }
            case PATCH -> {
                String snapshot = matcher.group("snapshot");
                if (snapshot == null) patch++;
            }
        }
        return String.format("%d.%d.%d", major, minor, patch);
    }

    String calculateJavaVersion(List<PomHolder> poms) {
        Set<String> propsToSearch = Set.of("maven.compiler.source", "maven.compiler.target", "maven.compiler.release");
        List<PomHolder> effectivePoms = poms.stream().map(ph -> {
            String artifact = String.format("%s:%s", ph.getGroupId(), ph.getArtifactId());
            Model effectivePom = getEffectivePom(ph.getPath().getParent(), artifact);
            PomHolder pomHolder = new PomHolder();
            pomHolder.setPath(ph.getPath());
            pomHolder.setModel(effectivePom);
            return pomHolder;
        }).toList();
        List<PomHolder> updateEffectivePoms = effectivePoms.stream().peek(pom -> {
            Parent parent = pom.getModel().getParent();
            if (parent != null) {
                String groupId = parent.getGroupId();
                String artifactId = parent.getArtifactId();
                effectivePoms.stream().filter(ph -> Objects.equals(ph.getGroupId(), groupId) && Objects.equals(ph.getArtifactId(), artifactId))
                        .findFirst().ifPresent(pom::setParent);
            }
        }).toList();
        // first search among plugins in effective-poms
        Optional<String> javaVersionFromMavenCompilerPlugin = updateEffectivePoms.stream().map(ph -> {
                    Map<String, String> compilerPluginConfigProps = ph.getModel().getBuild().getPlugins().stream()
                            .filter(plugin -> plugin.getArtifactId().equals("maven-compiler-plugin") && plugin.getConfiguration() instanceof Xpp3Dom)
                            .flatMap(plugin -> {
                                Map<String, String> result = new HashMap<>();
                                Xpp3Dom configuration = (Xpp3Dom) plugin.getConfiguration();
                                Optional.ofNullable(configuration.getChild("release")).map(Xpp3Dom::getValue).ifPresentOrElse(r -> result.put("release", r),
                                        () -> {
                                            Optional.ofNullable(configuration.getChild("target")).map(Xpp3Dom::getValue).ifPresent(r -> result.put("target", r));
                                            Optional.ofNullable(configuration.getChild("source")).map(Xpp3Dom::getValue).ifPresent(r -> result.put("source", r));
                                        });
                                return result.entrySet().stream();
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                    (s1, s2) -> {
                                        if (!Objects.equals(s1, s2)) {
                                            throw new IllegalStateException(String.format("Different java versions %s and %s specified for maven-compiler-plugin in pom: %s",
                                                    s1, s2, String.format("%s:%s", ph.getGroupId(), ph.getArtifactId())));
                                        } else {
                                            return s1;
                                        }
                                    }));
                    return compilerPluginConfigProps.getOrDefault("release",
                            compilerPluginConfigProps.getOrDefault("target",
                                    compilerPluginConfigProps.get("source")));
                })
                .filter(Objects::nonNull)
                .findFirst();
        if (javaVersionFromMavenCompilerPlugin.isPresent()) {
            return javaVersionFromMavenCompilerPlugin.get();
        }
        Map<String, String> mergedProperties = updateEffectivePoms.stream()
                .flatMap(ph -> ph.getModel().getProperties().entrySet().stream())
                .filter(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String)
                .filter(entry -> propsToSearch.contains(entry.getKey()))
                .collect(Collectors.toMap(entry -> ((String) entry.getKey()).replace("maven.compiler.", ""),
                        entry -> (String) entry.getValue(),
                        (s1, s2) -> {
                            if (!Objects.equals(s1, s2)) {
                                throw new IllegalStateException(String.format("Different java versions %s and %s specified in properties in poms: %s",
                                        s1, s2, String.join(", ", updateEffectivePoms.stream().map(ph -> String.format("%s:%s", ph.getGroupId(), ph.getArtifactId())).toList())));
                            } else {
                                return s1;
                            }
                        }));
        String versionFromProperties = mergedProperties.getOrDefault("release",
                mergedProperties.getOrDefault("target",
                        mergedProperties.get("source")));
        if (versionFromProperties == null) {
            throw new IllegalStateException("Failed to resolve java version neither from maven-compiler-plugin's configuration nor from 'maven.compiler.xxx' properties");
        }
        return versionFromProperties;
    }

    void updateDependencies(String baseDir, RepositoryInfo repositoryInfo, List<PomHolder> poms, Collection<GAV> dependencies) {
        updateDepVersionsNew(repositoryInfo, poms, dependencies);
        // check all versions were updated
        Predicate<GA> filter = ga -> dependencies.stream().anyMatch(gav -> gav.getGroupId().equals(ga.getGroupId()) && gav.getArtifactId().equals(ga.getArtifactId()));
        List<PomHolder> updatedPoms = getPoms(baseDir, repositoryInfo);
        resolveDependencies(repositoryInfo, updatedPoms, filter);
        Set<GAV> updatedModuleDependencies = repositoryInfo.getModuleDependencies();
        Set<GAV> missedDependencies = updatedModuleDependencies.stream()
                .filter(gav -> {
                    Optional<GAV> foundGav = dependencies.stream()
                            .filter(dGav -> Objects.equals(gav.getGroupId(), dGav.getGroupId()) && Objects.equals(gav.getArtifactId(), dGav.getArtifactId())).findFirst();
                    if (foundGav.isEmpty()) return false;
                    GAV g = foundGav.get();
                    return !Objects.equals(gav.getVersion(), g.getVersion());
                })
                .collect(Collectors.toSet());
        if (!missedDependencies.isEmpty()) {
            throw new RuntimeException("Failed to update dependencies: " + missedDependencies.stream().map(GAV::toString).collect(Collectors.joining("\n")));
        }
        commitUpdatedDependenciesIfAny(baseDir, repositoryInfo);
    }

    Function<Model, GA> pomGAFunction = pom -> {
        String groupId = pom.getArtifactId();
        String artifactId = Optional.ofNullable(pom.getGroupId()).orElseGet(() -> {
            // get groupIg from parent tag
            return Optional.ofNullable(pom.getParent()).map(Parent::getGroupId)
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("Invalid pom with attributeId: '%s' - no groupId or no parent",
                                    groupId)));
        });
        return new GA(artifactId, groupId);
    };

    void updateDepVersionsNew(RepositoryInfo repositoryInfo, List<PomHolder> poms, Collection<GAV> dependencies) {
        Map<String, List<GAV>> propertiesToDependencies = new HashMap<>();
        Map<String, Set<PomHolder>> propertiesToPropertiesNodes = new HashMap<>();
        BiConsumer<PomHolder, Dependency> depFunction = (holder, dependency) -> {
            String groupId = dependency.getGroupId();
            if (groupId == null) {
                return;
            }
            String artifactId = dependency.getArtifactId();
            String version = dependency.getVersion();
            GA dependencyGA = new GA(groupId, artifactId);
            GAV newGav = dependencies.stream()
                    // exclude our's own modules
                    .filter(gav -> repositoryInfo.getModules().stream().noneMatch(ga -> Objects.equals(ga.getGroupId(), gav.getGroupId()) && Objects.equals(ga.getArtifactId(), gav.getArtifactId())))
                    .filter(gav -> Objects.equals(gav.getGroupId(), dependencyGA.getGroupId()) &&
                                   Objects.equals(gav.getArtifactId(), dependencyGA.getArtifactId()))
                    .findFirst().orElse(null);
            if (version != null && newGav != null) {
                Matcher matcher = propertyPattern.matcher(version);
                if (matcher.matches()) {
                    String propertyName = matcher.group(1);
                    List<GAV> dependenciesList = propertiesToDependencies.computeIfAbsent(propertyName, k -> new ArrayList<>());
                    dependenciesList.add(newGav);
                } else {
                    // update a hard-coded version right away
                    updateVersionInDependency(holder, newGav);
                }
            }
        };
        BiConsumer<PomHolder, Properties> propFunction = (holder, properties) -> {
            Map<String, String> props = properties.entrySet().stream()
                    .collect(Collectors.toMap(entry -> (String) entry.getKey(), entry -> (String) entry.getValue()));
            props.forEach((propertyName, propertyValue) -> {
                if (propertiesToDependencies.containsKey(propertyName)) {
                    propertiesToPropertiesNodes.computeIfAbsent(propertyName, k -> new HashSet<>()).add(holder);
                }
            });
        };
        poms.forEach(ph -> {
            Optional.ofNullable(ph.getModel().getDependencyManagement())
                    .map(DependencyManagement::getDependencies)
                    .ifPresent(d -> d.forEach(dep -> depFunction.accept(ph, dep)));
            Optional.ofNullable(ph.getModel().getDependencies())
                    .ifPresent(d -> d.forEach(dep -> depFunction.accept(ph, dep)));

            PomHolder pomHolderWithProperties = ph;
            while (pomHolderWithProperties != null) {
                Model pomWithProperties = pomHolderWithProperties.getModel();
                Optional.ofNullable(pomWithProperties.getProperties())
                        .ifPresent(p -> propFunction.accept(ph, p));
                pomHolderWithProperties = pomHolderWithProperties.getParent();
            }
        });
        if (!propertiesToPropertiesNodes.isEmpty()) {
            propertiesToPropertiesNodes.forEach((propertyName, propertyNodes) -> {
                // make sure that property is referencing the same version for all found dependencies
                List<GAV> propGavs = propertiesToDependencies.get(propertyName);
                Map<String, Set<GAV>> versionToGavs = propGavs.stream().collect(Collectors.toMap(GAV::getVersion, Set::of,
                        (s1, s2) -> Stream.concat(s1.stream(), s2.stream()).collect(Collectors.toSet())));
                if (versionToGavs.size() != 1) {
                    throw new IllegalStateException(String.format("Invalid property '%s' - references by GAVs with different 'update to' versions: %s",
                            propertyName, versionToGavs));
                }
                String version = versionToGavs.keySet().iterator().next();
                // update property value
                propertyNodes.forEach(pom -> updatePropertyInPom(pom, propertyName, version));
            });
        }
        poms.forEach(pom -> {
            try {
                Files.writeString(pom.getPath(), pom.getPom(), StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    void updateVersionInDependency(PomHolder pom, GAV gav) {
        Pattern dependencyPattern = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
        Pattern groupIdPattern = Pattern.compile("<groupId>(.+)</groupId>");
        Pattern artifactIdPattern = Pattern.compile("<artifactId>(.+)</artifactId>");
        Pattern versionPattern = Pattern.compile("<version>(.+)</version>");
        String pomContent = pom.getPom();
        Matcher matcher = dependencyPattern.matcher(pomContent);

        Map<String, String> properties = new HashMap<>();
        while (matcher.find()) {
            String dependency = matcher.group();
            String dependencyContent = matcher.group(1);
            Matcher groupIdMatcher = groupIdPattern.matcher(dependencyContent);
            Matcher artifactIdMatcher = artifactIdPattern.matcher(dependencyContent);
            Matcher versionMatcher = versionPattern.matcher(dependencyContent);
            if (groupIdMatcher.find() && artifactIdMatcher.find() && versionMatcher.find()) {
                String groupId = autoResolveReferencedValue(pom, groupIdMatcher.group(1), properties);
                String artifactId = autoResolveReferencedValue(pom, artifactIdMatcher.group(1), properties);
                String version = versionMatcher.group(1);
                if (groupId.equals(gav.getGroupId()) && artifactId.equals(gav.getArtifactId())) {
                    pomContent = pomContent.replace(dependency, dependency.replace(version, gav.getVersion()));
                }
            }
        }
        pom.setPom(pomContent);
    }

    String autoResolveReferencedValue(PomHolder pom, String value, Map<String, String> properties) {
        if (value == null) return null;
        Pattern referencePattern = Pattern.compile("\\$\\{([^<>]+)}");
        Matcher referenceMatcher = referencePattern.matcher(value);
        if (referenceMatcher.find()) {
            // find reference among properties
            String prop = referenceMatcher.group(1);
            if (properties.isEmpty()) properties.putAll(getProperties(pom));
            String valueFromProperty = properties.get(prop);
            if (valueFromProperty != null) {
                return valueFromProperty;
            } else {
                return value;
            }
        }
        return value;
    }

    Map<String, String> getProperties(PomHolder ph) {
        Map<String, String> properties = new HashMap<>();
        PomHolder pomHolderWithProperties = ph;
        while (pomHolderWithProperties != null) {
            Model pomWithProperties = pomHolderWithProperties.getModel();
            properties.putAll(pomWithProperties.getProperties().entrySet().stream()
                    .collect(Collectors.toMap(entry -> (String) entry.getKey(), entry -> (String) entry.getValue())));
            pomHolderWithProperties = pomHolderWithProperties.getParent();
        }
        return properties;
    }

    void updatePropertyInPom(PomHolder pom, String name, String version) {
        Pattern propertiesPattern = Pattern.compile("(?s)<properties>(.+?)</properties>");
        Pattern propertyPattern = Pattern.compile(MessageFormat.format("<{0}>(.+)</{0}>", name));
        String pomContent = pom.getPom();
        Matcher matcher = propertiesPattern.matcher(pomContent);
        while (matcher.find()) {
            String properties = matcher.group();
            String propertiesContent = matcher.group(1);
            Matcher propMatcher = propertyPattern.matcher(propertiesContent);
            String newVersionTag = MessageFormat.format("<{0}>{1}</{0}>", name, version);
            while (propMatcher.find()) {
                String oldVersionTag = propMatcher.group();
                pomContent = pomContent.replace(properties, properties.replace(oldVersionTag, newVersionTag));
            }
        }
        pom.setPom(pomContent);
    }

    void commitUpdatedDependenciesIfAny(String baseDir, RepositoryInfo repository) {
        Path repositoryDirPath = Paths.get(baseDir, repository.getDir());
        try {
            try (Git git = Git.open(repositoryDirPath.toFile())) {
                List<DiffEntry> diff = git.diff().call();
                if (diff.stream().anyMatch(d -> d.getChangeType() == DiffEntry.ChangeType.MODIFY &&
                                                Arrays.asList(d.getNewPath().split("/")).contains("pom.xml"))) {
                    git.add().setUpdate(true).call();
                    git.commit().setMessage("updating dependencies before release").call();
                }
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Release releasePrepare(RepositoryInfo repositoryInfo, Config config, String releaseVersion, String javaVersion) {
        Path repositoryDirPath = Paths.get(config.getBaseDir(), repositoryInfo.getDir());
        Path outputFilePath = Paths.get(repositoryDirPath.toString(), "release-prepare-output.log");

        List<String> arguments = new ArrayList<>();
        if (config.isRunTests()) {
            arguments.add("surefire.rerunFailingTestsCount=2");
        } else {
            arguments.add("skipTests");
        }
        List<String> cmd = List.of("mvn", "-B", "release:prepare",
                "-Dresume=false",
                "-DautoVersionSubmodules=true",
                "-DreleaseVersion=" + releaseVersion,
                "-DpushChanges=false",
                warpPropertyInQuotes(String.format("-Darguments=%s", String.join(" ", arguments.stream().map(arg -> "-D" + arg).toList()))),
                warpPropertyInQuotes("-DtagNameFormat=@{project.version}"),
                warpPropertyInQuotes("-DpreparationGoals=clean install"));
        log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));
        try {
            Files.writeString(outputFilePath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
            String javaHome = config.getJavaVersionToJavaHomeEnv().get(javaVersion);
            if (javaHome != null) {
                processBuilder.environment().put("JAVA_HOME", javaHome);
            }
            Process process = processBuilder.start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStream umbrellaOutStream = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    baos.write(b);
                    byte[] byteArray = new byte[]{(byte) b};
                    Files.write(outputFilePath, byteArray, StandardOpenOption.APPEND);
                }
            };
            process.getInputStream().transferTo(umbrellaOutStream);
            process.getErrorStream().transferTo(umbrellaOutStream);
            process.waitFor();
            log.info("Repository: {}\nCmd: '{}' ended with code: {}, output: {}",
                    repositoryInfo.getUrl(), String.join(" ", cmd), process.exitValue(), baos);
            if (process.exitValue() != 0) {
                throw new RuntimeException(String.format("Failed to execute cmd, error: %s", baos));
            }
            List<GAV> gavs = Files.readString(Paths.get(repositoryDirPath.toString(), "release.properties")).lines()
                    .filter(l -> l.startsWith("project.rel."))
                    .map(l -> l.replace("project.rel.", "")
                            .replace("\\", "")
                            .replace("=", ":"))
                    .map(GAV::new).toList();
            Release release = new Release();
            release.setRepository(repositoryInfo);
            release.setReleaseVersion(releaseVersion);
            release.setJavaVersion(javaVersion);
            release.setGavs(gavs);
            return release;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    String warpPropertyInQuotes(String prop) {
        return String.format("\"%s\"", prop);
    }

    void performRelease(Config config, Release release) {
        try {
            String baseDir = config.getBaseDir();
            RepositoryInfo repository = release.getRepository();
            String releaseVersion = release.getReleaseVersion();
            Path repositoryDirPath = Paths.get(baseDir, repository.getDir());
            Path outputFilePath = Paths.get(repositoryDirPath.toString(), "release-perform-output.log");
            Files.writeString(outputFilePath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            pushChanges(baseDir, repository, releaseVersion);
            releaseDeploy(baseDir, repository, outputFilePath, config, release.getJavaVersion());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void pushChanges(String baseDir, RepositoryInfo repositoryInfo, String releaseVersion) {
        Path repositoryDirPath = Paths.get(baseDir, repositoryInfo.getDir());
        try (Git git = Git.open(repositoryDirPath.toFile())) {
            Optional<Ref> tagOpt = git.tagList().call().stream()
                    .filter(t -> t.getName().equals(String.format("refs/tags/%s", releaseVersion)))
                    .findFirst();
            if (tagOpt.isEmpty()) {
                throw new IllegalStateException(String.format("git tag: %s not found", releaseVersion));
            }
            git.push().setCredentialsProvider(credentialsProvider)
                    .setRemote("origin")
                    .setPushAll()
                    .setPushTags()
                    .call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void releaseDeploy(String baseDir, RepositoryInfo repositoryInfo, Path outputFilePath, Config config, String javaVersion) {
        try {
            Path repositoryDirPath = Paths.get(baseDir, repositoryInfo.getDir());
            List<String> arguments = new ArrayList<>();
            arguments.add("skipTests");
            List<String> cmd = List.of("mvn", "-B", "release:perform", "-DlocalCheckout=true", "-DautoVersionSubmodules=true",
                    warpPropertyInQuotes(String.format("-Darguments=%s", String.join(" ", arguments.stream().map(arg -> "-D" + arg).toList()))));
            log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));

            ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
            String javaHome = config.getJavaVersionToJavaHomeEnv().get(javaVersion);
            if (javaHome != null) {
                processBuilder.environment().put("JAVA_HOME", javaHome);
            }
            Process process = processBuilder.start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStream umbrellaOutStream = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    baos.write(b);
                    byte[] byteArray = new byte[]{(byte) b};
                    Files.write(outputFilePath, byteArray, StandardOpenOption.APPEND);
                }
            };
            process.getInputStream().transferTo(umbrellaOutStream);
            process.getErrorStream().transferTo(umbrellaOutStream);
            process.waitFor();
            log.info("Repository: {}\nCmd: '{}' ended with code: {}, output: {}",
                    repositoryInfo.getUrl(), String.join(" ", cmd), process.exitValue(), baos);
            if (process.exitValue() != 0) {
                throw new RuntimeException(String.format("Failed to execute cmd, error: %s", baos));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    String generateDotFile(Map<Integer, List<RepositoryInfo>> dependencyGraph) {
        Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);
        List<RepositoryInfo> repositoryInfoList = dependencyGraph.values().stream().flatMap(Collection::stream).toList();
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            graph.addVertex(repositoryInfo.getUrl());
        }
        for (RepositoryInfo repositoryInfo : repositoryInfoList) {
            repositoryInfo.getRepoDependencies().forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
        }
        Function<String, String> vertexIdProvider = vertex -> {
            return String.format("\"%s\"", vertex.replace("https://github.com/Netcracker/", ""));
        };
        DOTExporter<String, StringEdge> exporter = new DOTExporter<>(vertexIdProvider);
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            exporter.exportGraph(graph, stream);
            return stream.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
