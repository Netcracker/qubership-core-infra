package org.qubership.cloud.actions.maven;

import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
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
import org.qubership.cloud.actions.maven.model.Repository;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
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

    public Result prepare(Config config) {
        Result result = new Result();
        Map<GA, String> dependenciesGavs = config.getGavs().stream().map(GAV::new).collect(Collectors.toMap(gav -> new GA(gav.getGroupId(), gav.getArtifactId()), GAV::getVersion));
        // build dependency graph
        Map<Integer, List<RepositoryInfo>> dependencyGraph = buildDependencyGraph(config);
        String dot = generateDotFile(dependencyGraph);
        result.setDependenciesDot(dot);

        List<Release> allReleases = dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey();
            log.info("Processing level {}/{}, {} repositories:\n{}", level + 1, dependencyGraph.size(), entry.getValue().size(),
                    String.join("\n", entry.getValue().stream().map(Repository::getUrl).toList()));
            List<RepositoryInfo> reposInfoList = entry.getValue();
            int threads = reposInfoList.size();
//            int threads = 1;
            try (ExecutorService executorService = Executors.newFixedThreadPool(threads)) {
                Set<GAV> gavList = dependenciesGavs.entrySet().stream().map(e -> new GAV(e.getKey().getGroupId(), e.getKey().getArtifactId(), e.getValue())).collect(Collectors.toSet());
                List<Release> releases = reposInfoList.stream()
                        .map(repo -> executorService.submit(() -> prepare(config, repo, gavList)))
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
        try (ExecutorService executorService = Executors.newFixedThreadPool(8)) {
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
            // set repository dependencies
            for (RepositoryInfo repositoryInfo : repositoryInfoList.stream().filter(ri -> !ri.getModuleDependencies().isEmpty()).toList()) {
                Set<GA> moduleDependencies = repositoryInfo.getModuleDependencies().stream()
                        .map(gav -> new GA(gav.getGroupId(), gav.getArtifactId()))
                        .collect(Collectors.toSet());
                repositoryInfo.getRepoDependencies().addAll(moduleDependencies.stream()
                        .flatMap(ga -> repositoryInfoList.stream().filter(ri -> ri.getModules().contains(ga)))
                        .filter(repo -> !Objects.equals(repo.getUrl(), repositoryInfo.getUrl()))
                        .collect(Collectors.toSet()));
            }

            List<RepositoryInfo> repositoryInfos = Optional.ofNullable(config.getRepositoriesToReleaseFrom())
                    .map(repositoriesToReleaseFrom -> repositoryInfoList.stream()
                            // filter repositories which are not affected by 'released from' repositories
                            .filter(ri -> repositoriesToReleaseFrom.contains(ri.getUrl()) || repositoriesToReleaseFrom.stream()
                                    .anyMatch(riFrom -> ri.getRepoDependenciesFlatSet().stream()
                                            .map(Repository::getUrl).collect(Collectors.toSet()).contains(riFrom)))
                            .toList())
                    .orElse(repositoryInfoList);

            Graph<String, StringEdge> graph = new SimpleDirectedGraph<>(StringEdge.class);

            for (RepositoryInfo repositoryInfo : repositoryInfos) {
                graph.addVertex(repositoryInfo.getUrl());
            }
            for (RepositoryInfo repositoryInfo : repositoryInfos) {
                repositoryInfo.getRepoDependenciesFlatSet()
                        .stream()
                        .filter(ri-> repositoryInfos.stream().anyMatch(riFrom -> Objects.equals(riFrom.getUrl(), ri.getUrl())))
                        .forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
            }

            List<RepositoryInfo> independentRepos = repositoryInfos.stream().filter(ri -> graph.incomingEdgesOf(ri.getUrl()).isEmpty()).toList();
            List<RepositoryInfo> dependentRepos = repositoryInfos.stream().filter(ri -> !graph.incomingEdgesOf(ri.getUrl()).isEmpty()).collect(Collectors.toList());
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
                        String content = Files.readString(file);
                        PomHolder pomHolder = new PomHolder(content, file);
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
        return poms.stream().peek(pom -> {
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
                Model project = pomHolder.getModel();
                GA projectGA = pomGAFunction.apply(project);
                String projectGroupId = pomHolder.autoResolvePropReference(projectGA.getGroupId());
                String projectArtifactId = pomHolder.autoResolvePropReference(projectGA.getArtifactId());
                GA moduleGA = new GA(projectGroupId, projectArtifactId);
                repositoryInfo.getModules().add(moduleGA);
            }
            for (PomHolder pomHolder : poms) {
                Model project = pomHolder.getModel();
                List<Dependency> dependencyManagementNodes = Optional.ofNullable(project.getDependencyManagement())
                        .map(DependencyManagement::getDependencies)
                        .orElse(List.of());
                List<Dependency> dependenciesNodes = Optional.ofNullable(project.getDependencies()).orElse(List.of());
                List<Dependency> allDependenciesNodes = Stream.concat(dependencyManagementNodes.stream(), dependenciesNodes.stream()).toList();
                for (Dependency dependency : allDependenciesNodes) {
                    String groupId = pomHolder.autoResolvePropReference(dependency.getGroupId());
                    String artifactId = pomHolder.autoResolvePropReference(dependency.getArtifactId());
                    String version = pomHolder.autoResolvePropReference(dependency.getVersion());
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

    Release prepare(Config config, RepositoryInfo repository, Collection<GAV> dependencies) {
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
        Set<String> propsToSearch = Set.of("maven.compiler.source", "maven.compiler.target", "maven.compiler.release", "java.version");
        // first search among plugins in poms
        Optional<String> versionFromPlugin = poms.stream().map(ph -> {
                    Map<String, String> props = Optional.ofNullable(ph.getModel().getBuild()).map(PluginContainer::getPlugins).orElse(List.of()).stream()
                            .filter(plugin -> plugin.getArtifactId().equals("maven-compiler-plugin") && plugin.getConfiguration() instanceof Xpp3Dom)
                            .flatMap(plugin -> {
                                Map<String, String> result = new HashMap<>();
                                Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
                                Optional.ofNullable(config.getChild("release"))
                                        .map(Xpp3Dom::getValue)
                                        .map(ph::autoResolvePropReference)
                                        .ifPresent(r -> result.put("release", r));
                                Optional.ofNullable(config.getChild("target"))
                                        .map(Xpp3Dom::getValue)
                                        .map(ph::autoResolvePropReference)
                                        .ifPresent(r -> result.put("target", r));
                                Optional.ofNullable(config.getChild("source"))
                                        .map(Xpp3Dom::getValue)
                                        .map(ph::autoResolvePropReference)
                                        .ifPresent(r -> result.put("source", r));
                                return result.entrySet().stream();
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (s1, s2) -> {
                                if (!Objects.equals(s1, s2)) {
                                    throw new IllegalStateException(String.format("Different java versions %s and %s specified for maven-compiler-plugin in pom: %s",
                                            s1, s2, String.format("%s:%s", ph.getGroupId(), ph.getArtifactId())));
                                } else {
                                    return s1;
                                }
                            }));
                    return props.getOrDefault("release", props.getOrDefault("target", props.get("source")));
                })
                .filter(Objects::nonNull)
                .findFirst();
        if (versionFromPlugin.isPresent()) {
            return versionFromPlugin.get();
        }
        Map<String, String> props = poms.stream()
                .flatMap(ph -> ph.getProperties().entrySet().stream())
                .filter(entry -> propsToSearch.contains(entry.getKey()))
                .collect(Collectors.toMap(entry -> entry.getKey()
                                .replace("maven.compiler.", "")
                                .replace("java.version", "release"), Map.Entry::getValue,
                        (s1, s2) -> {
                            if (!Objects.equals(s1, s2)) {
                                throw new IllegalStateException(String.format("Different java versions %s and %s specified in properties in poms: %s",
                                        s1, s2, String.join("\n", poms.stream().map(ph -> String.format("%s:%s", ph.getGroupId(), ph.getArtifactId())).toList())));
                            }
                            return s1;
                        }));
        return props.getOrDefault("release", props.getOrDefault("target", props.get("source")));
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
                    holder.updateVersionInDependency(newGav);
                }
            }
        };
        Consumer<PomHolder> propFunction = (holder) -> {
            holder.getProperties().forEach((propertyName, propertyValue) -> {
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
            propFunction.accept(ph);
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
                propertyNodes.forEach(pom -> pom.updateProperty(propertyName, version));
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
                "-Dtag=" + releaseVersion,
                warpPropertyInQuotes("-DtagNameFormat=@{project.version}"),
                warpPropertyInQuotes(String.format("-Darguments=%s", String.join(" ", arguments.stream().map(arg -> "-D" + arg).toList()))),
                warpPropertyInQuotes("-DpreparationGoals=clean install"));
        log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));
        try {
            Files.writeString(outputFilePath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
            Optional.ofNullable(javaVersion).map(v -> config.getJavaVersionToJavaHomeEnv().get(v))
                    .ifPresent(javaHome -> processBuilder.environment().put("JAVA_HOME", javaHome));
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
            if (config.getMavenAltDeploymentRepository() != null) {
                arguments.add("altDeploymentRepository=" + config.getMavenAltDeploymentRepository());
            }
            String argsString = String.join(" ", arguments.stream().map(arg -> "-D" + arg).toList());
            List<String> cmd = Stream.of("mvn", "-B", "release:perform",
                            "-DlocalCheckout=true",
                            "-DautoVersionSubmodules=true",
                            warpPropertyInQuotes(String.format("-Darguments=%s", argsString)))
                    .collect(Collectors.toList());
            log.info("Repository: {}\nCmd: '{}' started", repositoryInfo.getUrl(), String.join(" ", cmd));

            ProcessBuilder processBuilder = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile());
            Optional.ofNullable(javaVersion).map(v -> config.getJavaVersionToJavaHomeEnv().get(v))
                    .ifPresent(javaHome -> processBuilder.environment().put("JAVA_HOME", javaHome));
            // maven envs
            if (config.getMavenUser() != null && config.getMavenPassword() != null) {
                processBuilder.environment().put("MAVEN_USER", config.getMavenUser());
                processBuilder.environment().put("MAVEN_TOKEN", config.getMavenPassword());
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
            repositoryInfo.getRepoDependencies()
                    .stream()
                    .filter(ri-> dependencyGraph.values().stream().flatMap(Collection::stream).anyMatch(ri2 -> Objects.equals(ri2.getUrl(), ri.getUrl())))
                    .forEach(ri -> graph.addEdge(ri.getUrl(), repositoryInfo.getUrl()));
        }
        Function<String, String> vertexIdProvider = vertex ->
                String.format("\"%s\"", vertex.replace("https://github.com/Netcracker/", ""));
        DOTExporter<String, StringEdge> exporter = new DOTExporter<>(vertexIdProvider);
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            exporter.exportGraph(graph, stream);
            return stream.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
