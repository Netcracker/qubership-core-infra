package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.OutputStream;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Data
@ToString(exclude = "mavenPassword")
public class Config {
    final String baseDir;
    final GitConfig gitConfig;
    @JsonIgnore
    final CredentialsProvider credentialsProvider;
    // all repositories
    final Set<String> repositories;
    final Predicate<GA> dependenciesFilter;
    Collection<String> gavs;
    VersionIncrementType versionIncrementType = VersionIncrementType.PATCH;
    Map<String, String> javaVersionToJavaHomeEnv;
    // particular repository(ies) to start release from (the rest of the tree will be calculated automatically)
    Set<String> repositoriesToReleaseFrom = new LinkedHashSet<>();
    String mavenUser;
    @JsonIgnore
    String mavenPassword;
    String mavenAltDeploymentRepository;
    boolean skipTests;
    boolean dryRun;
    @JsonIgnore
    OutputStream summaryOutputStream;

    @Builder(builderMethodName = "")
    private Config(String baseDir,
                   GitConfig gitConfig,
                   CredentialsProvider credentialsProvider,
                   Set<String> repositories,
                   Predicate<GA> dependenciesFilter,
                   Collection<String> gavs,
                   VersionIncrementType versionIncrementType,
                   Map<String, String> javaVersionToJavaHomeEnv,
                   Set<String> repositoriesToReleaseFrom,
                   String mavenUser,
                   String mavenPassword,
                   String mavenAltDeploymentRepository,
                   boolean skipTests,
                   boolean dryRun,
                   OutputStream summaryOutputStream) {
        this.baseDir = baseDir;
        this.gitConfig = gitConfig;
        this.credentialsProvider = credentialsProvider;
        this.dependenciesFilter = dependenciesFilter;
        this.gavs = gavs;
        this.javaVersionToJavaHomeEnv = javaVersionToJavaHomeEnv;
        this.mavenAltDeploymentRepository = mavenAltDeploymentRepository;
        this.mavenPassword = mavenPassword;
        this.mavenUser = mavenUser;
        this.repositories = repositories.stream().map(normalizeGitUrl).collect(Collectors.toSet());
        this.repositoriesToReleaseFrom = repositoriesToReleaseFrom.stream().map(normalizeGitUrl).collect(Collectors.toSet());
        this.skipTests = skipTests;
        this.dryRun = dryRun;
        this.versionIncrementType = versionIncrementType;
        this.summaryOutputStream = summaryOutputStream;
    }

    public static ConfigBuilder builder(String baseDir,
                                        GitConfig gitConfig,
                                        Set<String> repositories,
                                        Predicate<GA> dependenciesFilter) {
        CredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(gitConfig.getUsername(), gitConfig.getPassword());
        CredentialsProvider.setDefault(credentialsProvider);
        return new ConfigBuilder()
                .baseDir(baseDir)
                .gitConfig(gitConfig)
                .credentialsProvider(credentialsProvider)
                .repositories(repositories)
                .dependenciesFilter(dependenciesFilter)
                // set by default NOP OutputStream
                .summaryOutputStream(new OutputStream() {
                    @Override
                    public void write(int b) {
                    }
                });
    }

    public Collection<String> getGavs() {
        return gavs == null ? Collections.emptyList() : gavs;
    }

    public Map<String, String> getJavaVersionToJavaHomeEnv() {
        return javaVersionToJavaHomeEnv == null ? Collections.emptyMap() : javaVersionToJavaHomeEnv;
    }

    public Set<String> getRepositoriesToReleaseFrom() {
        return repositoriesToReleaseFrom == null ? Collections.emptySet() : repositoriesToReleaseFrom;
    }

    Function<String, String> normalizeGitUrl = (url) -> url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
}
