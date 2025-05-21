package org.qubership.cloud.actions.maven.model;

import lombok.Builder;
import lombok.Data;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.util.*;
import java.util.function.Predicate;

@Data
public class Config {
    final String baseDir;
    final GitConfig gitConfig;
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
    String mavenPassword;
    String mavenAltDeploymentRepository;
    boolean skipTests;
    boolean dryRun;

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
                   boolean dryRun) {
        this.baseDir = baseDir;
        this.gitConfig = gitConfig;
        this.credentialsProvider = credentialsProvider;
        this.dependenciesFilter = dependenciesFilter;
        this.gavs = gavs;
        this.javaVersionToJavaHomeEnv = javaVersionToJavaHomeEnv;
        this.mavenAltDeploymentRepository = mavenAltDeploymentRepository;
        this.mavenPassword = mavenPassword;
        this.mavenUser = mavenUser;
        this.repositories = repositories;
        this.repositoriesToReleaseFrom = repositoriesToReleaseFrom;
        this.skipTests = skipTests;
        this.dryRun = dryRun;
        this.versionIncrementType = versionIncrementType;
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
                .dependenciesFilter(dependenciesFilter);
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
}
