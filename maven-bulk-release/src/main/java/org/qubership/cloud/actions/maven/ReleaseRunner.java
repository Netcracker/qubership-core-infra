package org.qubership.cloud.actions.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.failsafe.RetryPolicy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jgrapht.Graph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.qubership.cloud.actions.maven.model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.time.Duration;
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

@Slf4j
public class ReleaseRunner {
    HttpClient httpClient = HttpClient.newBuilder().build();
    ObjectMapper yamlMapper = new ObjectMapper(new XmlFactory());
    XmlMapper xmlMapper = new XmlMapper();
    static Pattern propertyPattern = Pattern.compile("\\$\\{(.*?)}");

    UsernamePasswordCredentialsProvider credentialsProvider;

    public ReleaseRunner(String gitUsername, String gitPassword) {
        credentialsProvider = new UsernamePasswordCredentialsProvider(gitUsername, gitPassword);
        CredentialsProvider.setDefault(credentialsProvider);
    }

    RetryPolicy<Object> RETRY_POLICY = RetryPolicy.builder()
            .handle(AssertionError.class, Exception.class)
            .withMaxRetries(5)
            .withDelay(Duration.ofSeconds(1))
            .onRetry(e -> {
                Throwable lastFailure = e.getLastException();
                log.info("Retry #{} after: {} ms, lastFailure: {}",
                        e.getAttemptCount(), e.getElapsedAttemptTime().toMillis(),
                        lastFailure.getClass().getSimpleName() + " - " + lastFailure.getMessage());
            }).build();

    public List<GAV> prepare(String baseDir, List<String> repositories, Predicate<GA> dependenciesFilter, Collection<String> dependencies) {
        Map<GA, String> dependenciesGavs = dependencies.stream().map(GAV::new).collect(Collectors.toMap(gav -> new GA(gav.getGroupId(), gav.getArtifactId()), GAV::getVersion));
        // build dependency graph
        Map<Integer, List<RepositoryInfo>> dependencyGraph = buildDependencyGraph(baseDir, repositories, dependenciesFilter);

        return dependencyGraph.entrySet().stream().flatMap(entry -> {
            int level = entry.getKey();
            log.info("Processing level #{}, repositories:\n{}", level, String.join("\n", entry.getValue().stream().map(Repository::getUrl).toList()));
            List<RepositoryInfo> reposInfoList = entry.getValue();
//            try (ExecutorService executorService = Executors.newFixedThreadPool(reposInfoList.size())) {
            try (ExecutorService executorService = Executors.newFixedThreadPool(1)) {
                Set<GAV> gavList = dependenciesGavs.entrySet().stream().map(e -> new GAV(e.getKey().getGroupId(), e.getKey().getArtifactId(), e.getValue())).collect(Collectors.toSet());
                List<GAV> gavs = reposInfoList.stream()
                        .map(repo -> executorService.submit(() -> prepare(baseDir, repo, gavList)))
                        .toList()
                        .stream()
                        .flatMap(future -> {
                            try {
                                return future.get().stream();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            } catch (ExecutionException e) {
                                throw new RuntimeException(e);
                            }
                        }).toList();
                gavs.forEach(gav -> dependenciesGavs.put(new GA(gav.getGroupId(), gav.getArtifactId()), gav.getVersion()));
                return gavs.stream();
            }
        }).toList();
    }

    Map<Integer, List<RepositoryInfo>> buildDependencyGraph(String baseDir, List<String> repositories, Predicate<GA> dependenciesFilter) {
        try (ExecutorService executorService = Executors.newFixedThreadPool(5)) {
            List<RepositoryInfo> repositoryInfoList = repositories.stream().map(RepositoryInfo::new)
                    .map(repositoryInfo -> executorService.submit(() -> {
                        gitCheckout(baseDir, repositoryInfo);
                        String effectivePomContent = getEffectivePom(baseDir, repositoryInfo);
                        resolveDependencies(repositoryInfo, effectivePomContent, dependenciesFilter);
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
            // clone
            try (Git git = Git.cloneRepository()
                    .setCredentialsProvider(credentialsProvider)
                    .setURI(repository.getUrl())
                    .setDirectory(repositoryDirPath.toFile())
                    .call()) {
                // fetch
                FetchResult fetchResult = git.fetch().call();
                // merge
                MergeResult mergeResult = git.merge().include(fetchResult.getAdvertisedRef("refs/heads/main")).call();
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    String getEffectivePom(String baseDir, Repository repository) {
        Path repositoryDirPath = Paths.get(baseDir, repository.getDir());
        try {
            Files.deleteIfExists(Path.of(repositoryDirPath.toString(), "effective-pom.xml"));
            List<String> cmd = List.of("mvn", "help:effective-pom", "-Doutput=effective-pom.xml");
            log.info("Cmd: '{}' started", String.join(" ", cmd));
            Process process = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile()).start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            process.getInputStream().transferTo(baos);
            process.getErrorStream().transferTo(baos);
            process.waitFor();
            log.info("Cmd: '{}' ended with code: {}, output: {}", String.join(" ", cmd), process.exitValue(), baos);
            if (process.exitValue() != 0) {
                throw new RuntimeException(String.format("Failed to execute cmd, error: %s", baos));
            }
            return Files.readString(Path.of(repositoryDirPath.toString(), "effective-pom.xml"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<PomHolder> getPoms(Path repositoryDirPath) {
        List<PomHolder> poms = new ArrayList<>();
        try {
            Files.walkFileTree(repositoryDirPath, new FileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith("/pom.xml")) {
                        PomHolder pomHolder = new PomHolder();
                        String content = Files.readString(file);
                        pomHolder.setPath(file);
                        pomHolder.setPom(content);
                        pomHolder.setPomNode(xmlMapper.readTree(content));
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
        return poms;
    }

    void resolveDependencies(RepositoryInfo repositoryInfo, String effectivePomContent, Predicate<GA> dependenciesFilter) {
        repositoryInfo.getModules().clear();
        repositoryInfo.getModuleDependencies().clear();
        try {
            JsonNode effectivePom = xmlMapper.readTree(effectivePomContent);
            JsonNode projects = effectivePom.get("project");
            List<JsonNode> projectList;
            if (projects == null) {
                projectList = Collections.singletonList(effectivePom);
            } else if (projects instanceof ArrayNode projectsArrayNode) {
                projectList = new ArrayList<>(projectsArrayNode.size());
                for (JsonNode project : projectsArrayNode) {
                    projectList.add(project);
                }
            } else {
                projectList = Collections.singletonList(projects);
            }
            for (JsonNode project : projectList) {
                String projectGroupId = project.get("groupId").asText();
                String projectArtifactId = project.get("artifactId").asText();
                GA moduleGA = new GA(projectGroupId, projectArtifactId);
                repositoryInfo.getModules().add(moduleGA);
            }
            for (JsonNode project : projectList) {
                if (project.get("dependencyManagement") instanceof JsonNode dependencyManagement &&
                    dependencyManagement.get("dependencies") instanceof JsonNode dependenciesMap) {
                    List<JsonNode> dependencyList;
                    if (dependenciesMap.get("dependency") instanceof ArrayNode dependencyArrayNode) {
                        dependencyList = new ArrayList<>(dependencyArrayNode.size());
                        for (JsonNode dependency : dependencyArrayNode) {
                            dependencyList.add(dependency);
                        }
                    } else if (dependenciesMap.get("dependency") instanceof JsonNode dependencyNode) {
                        dependencyList = Collections.singletonList(dependencyNode);
                    } else {
                        dependencyList = Collections.emptyList();
                    }
                    for (JsonNode dependency : dependencyList) {
                        String groupId = dependency.get("groupId").asText();
                        String artifactId = dependency.get("artifactId").asText();
                        String version = dependency.get("version").asText();
                        GAV dependencyGAV = new GAV(groupId, artifactId, version);
                        GA dependencyGA = new GA(groupId, artifactId);
                        if (dependenciesFilter.test(dependencyGA) && !repositoryInfo.getModules().contains(dependencyGA)) {
                            repositoryInfo.getModuleDependencies().add(dependencyGAV);
                        }
                    }
                }
                if (project.get("dependencies") instanceof JsonNode dependenciesMap) {
                    List<JsonNode> dependencyList;
                    if (dependenciesMap.get("dependency") instanceof ArrayNode dependencyArrayNode) {
                        dependencyList = new ArrayList<>(dependencyArrayNode.size());
                        for (JsonNode dependency : dependencyArrayNode) {
                            dependencyList.add(dependency);
                        }
                    } else if (dependenciesMap.get("dependency") instanceof JsonNode dependencyNode) {
                        dependencyList = Collections.singletonList(dependencyNode);
                    } else {
                        dependencyList = Collections.emptyList();
                    }
                    for (JsonNode dependency : dependencyList) {
                        String groupId = dependency.get("groupId").asText();
                        String artifactId = dependency.get("artifactId").asText();
                        String version = dependency.get("version").asText();
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

    List<GAV> prepare(String baseDir, RepositoryInfo repository, Collection<GAV> dependencies) {
        updateDependencies(baseDir, repository, dependencies);
        return releasePrepare(baseDir, repository);
    }

    void updateDependencies(String baseDir, RepositoryInfo repositoryInfo, Collection<GAV> dependencies) {
        updateDepVersionsNew(baseDir, repositoryInfo, dependencies);
        // check all versions were updated
        String effectivePomContent = getEffectivePom(baseDir, repositoryInfo);
        Predicate<GA> filter = ga -> dependencies.stream().anyMatch(gav -> gav.getGroupId().equals(ga.getGroupId()) && gav.getArtifactId().equals(ga.getArtifactId()));
        resolveDependencies(repositoryInfo, effectivePomContent, filter);
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

    Function<JsonNode, GA> gaFunction = node -> {
        String groupId = node.get("artifactId").asText();
        String artifactId = Optional.ofNullable(node.get("groupId")).map(JsonNode::asText).orElseGet(() -> {
            // get groupIg from parent tag
            return Optional.ofNullable(node.get("parent")).map(p -> p.get("groupId")).map(JsonNode::asText)
                    .orElseThrow(() -> new IllegalStateException(
                            String.format("Invalid pom with attributeId: '%s' - no groupId or no parent",
                                    groupId)));
        });
        return new GA(artifactId, groupId);
    };

    void updateDepVersionsNew(String baseDir, RepositoryInfo repositoryInfo, Collection<GAV> dependencies) {
        Path repositoryDirPath = Paths.get(baseDir, repositoryInfo.getDir());
        List<PomHolder> poms = getPoms(repositoryDirPath);
        List<PomHolder> organizedPomHolders = poms.stream()
                .peek(pom -> {
                    GA ga = gaFunction.apply(pom.getPomNode());
                    pom.setGa(ga);
                    JsonNode parent = pom.getPomNode().get("parent");
                    if (parent != null) {
                        String groupId = parent.get("groupId").asText();
                        String artifactId = parent.get("artifactId").asText();
                        poms.stream().filter(ph -> {
                            GA phGa = gaFunction.apply(ph.getPomNode());
                            return Objects.equals(phGa.getGroupId(), groupId) && Objects.equals(phGa.getArtifactId(), artifactId);
                        }).findFirst().ifPresent(pom::setParent);
                    }
                })
                // start with leaf poms
                .sorted(Comparator.<PomHolder>comparingInt(p -> p.getParentsFlatList().size()).reversed())
                .toList();
        Map<String, List<GAV>> propertiesToDependencies = new HashMap<>();
        Map<String, Set<PomHolder>> propertiesToPropertiesNodes = new HashMap<>();
        BiConsumer<PomHolder, JsonNode> depFunction = (holder, dependency) -> {
            String groupId = Optional.ofNullable(dependency.get("groupId")).map(JsonNode::asText).orElse(null);
            if (groupId == null) {
                return;
            }
            String artifactId = dependency.get("artifactId").asText();
            String version = Optional.ofNullable(dependency.get("version")).map(JsonNode::asText).orElse(null);
            GA dependencyGA = new GA(groupId, artifactId);
            GAV newGav = dependencies.stream()
                    .filter(gav -> Objects.equals(gav.getGroupId(), dependencyGA.getGroupId()) &&
                                   Objects.equals(gav.getArtifactId(), dependencyGA.getArtifactId()))
                    .findFirst().orElse(null);
            if (version != null && newGav != null) {
                String newGavVersion = newGav.getVersion();
                if (!version.equals(newGavVersion)) {
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
            }
        };
        BiConsumer<PomHolder, JsonNode> propFunction = (holder, node) -> {
            Map<String, JsonNode> props = node.properties().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            props.forEach((propertyName, propertyValue) -> {
                if (propertiesToDependencies.containsKey(propertyName)) {
                    propertiesToPropertiesNodes.computeIfAbsent(propertyName, k -> new HashSet<>()).add(holder);
                }
            });
        };
        Function<JsonNode, List<JsonNode>> nodeToListFunction = d -> {
            List<JsonNode> nodes = new ArrayList<>();
            if (d instanceof ArrayNode arrayNodes) {
                for (JsonNode arrayNode : arrayNodes) {
                    nodes.add(arrayNode);
                }
            } else {
                nodes.add(d);
            }
            return nodes;
        };
        organizedPomHolders.forEach(ph -> {
            Optional.ofNullable(ph.getPomNode().get("dependencyManagement"))
                    .map(dm -> dm.get("dependencies"))
                    .map(d -> d.get("dependency"))
                    .map(nodeToListFunction)
                    .ifPresent(d -> d.forEach(dep -> depFunction.accept(ph, dep)));
            Optional.ofNullable(ph.getPomNode().get("dependencies"))
                    .map(d -> d.get("dependency"))
                    .map(nodeToListFunction)
                    .ifPresent(d -> d.forEach(dep -> depFunction.accept(ph, dep)));

            PomHolder pomHolderWithProperties = ph;
            while (pomHolderWithProperties != null) {
                JsonNode pomWithProperties = pomHolderWithProperties.getPomNode();
                Optional.ofNullable(pomWithProperties.get("properties"))
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
        organizedPomHolders.forEach(pom -> {
            try {
                Files.writeString(pom.getPath(), pom.getPom(), StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    void updateVersionInDependency(PomHolder pom, GAV gav) {
        Pattern dependencyPattern = Pattern.compile("(?s)<dependency>(.+)</dependency>");
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
        Pattern referencePattern = Pattern.compile("\\$\\{([^<>]+)}");
        Matcher referenceMatcher = referencePattern.matcher(value);
        if (referenceMatcher.find()) {
            // find groupId among properties
            String prop = referenceMatcher.group(1);
            if (properties.isEmpty()) properties.putAll(getProperties(pom));
            String valueFromProperty = properties.get(prop);
            if (valueFromProperty == null)
                throw new IllegalArgumentException("Failed to find value for reference: " + value);
            value = valueFromProperty;
        }
        return value;
    }

    Map<String, String> getProperties(PomHolder ph) {
        Map<String, String> properties = new HashMap<>();
        PomHolder pomHolderWithProperties = ph;
        while (pomHolderWithProperties != null) {
            JsonNode pomWithProperties = pomHolderWithProperties.getPomNode();
            properties.putAll(Optional.ofNullable(pomWithProperties.get("properties"))
                    .map(node -> node.properties().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().asText())))
                    .orElse(new HashMap<>()));
            pomHolderWithProperties = pomHolderWithProperties.getParent();
        }
        return properties;
    }

    void updatePropertyInPom(PomHolder pom, String name, String version) {
        Pattern propertiesPattern = Pattern.compile("(?s)<properties>(.+)</properties>");
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
                if (!diff.isEmpty()) {
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

    List<GAV> releasePrepare(String baseDir, RepositoryInfo repositoryInfo) {
        Path repositoryDirPath = Paths.get(baseDir, repositoryInfo.getDir());
        Path outputFilePath = Paths.get(repositoryDirPath.toString(), "release-prepare-output.log");
        List<String> cmd = List.of("mvn", "-B","release:prepare ", "-Dresume=false", "-DautoVersionSubmodules=true",
        "\"-Darguments=-Dsurefire.rerunFailingTestsCount=2 -DskipTest=true\"",
                "-DpushChanges=false", "\"-DpreparationGoals=clean install\"");
        log.info("Cmd: '{}' started", String.join(" ", cmd));
        try {
            Files.writeString(outputFilePath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            Process process = new ProcessBuilder(cmd).directory(repositoryDirPath.toFile()).start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStream umbrellaOutStream = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    baos.write(b);
                    byte[] byteArray = new byte[]{(byte)b};
                    Files.write(outputFilePath, byteArray, StandardOpenOption.APPEND);
                }
            };
            process.getInputStream().transferTo(umbrellaOutStream);
            process.getErrorStream().transferTo(umbrellaOutStream);
            process.waitFor();
            log.info("Cmd: '{}' ended with code: {}, output: {}", String.join(" ", cmd), process.exitValue(), baos);
            if (process.exitValue() != 0) {
                throw new RuntimeException(String.format("Failed to execute cmd, error: %s", baos));
            }
            return Files.readString(Paths.get(repositoryDirPath.toString(), "release.properties")).lines()
                    .filter(l -> l.startsWith("project.rel."))
                    .map(l -> l.replace("project.rel.", "")
                            .replace("\\", "")
                            .replace("=", ":"))
                    .map(GAV::new).toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void releasePerform(Repository repository) {

    }
}
