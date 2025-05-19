package org.qubership.cloud.actions.maven.model;

import lombok.Builder;
import lombok.Data;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.util.*;
import java.util.function.Predicate;

@Data
public class Config {
    final String baseDir;
    final CredentialsProvider credentialsProvider;
    // all repositories
    final Set<String> repositories;
    final Predicate<GA> dependenciesFilter;
    Collection<String> gavs = new ArrayList<>();
    VersionIncrementType versionIncrementType = VersionIncrementType.PATCH;
    Map<String, String> javaVersionToJavaHomeEnv = new HashMap<>();
    // particular repository(ies) to start release from (the rest of the tree will be calculated automatically)
    Set<String> repositoriesToReleaseFrom = new LinkedHashSet<>();
    String mavenUser;
    String mavenPassword;
    String mavenAltDeploymentRepository;
    boolean runTests;
    boolean runDeploy;

    @Builder(builderMethodName = "")
    private Config(String baseDir,
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
                  boolean runDeploy,
                  boolean runTests) {
        this.baseDir = baseDir;
        this.credentialsProvider = credentialsProvider;
        this.dependenciesFilter = dependenciesFilter;
        this.gavs = gavs;
        this.javaVersionToJavaHomeEnv = javaVersionToJavaHomeEnv;
        this.mavenAltDeploymentRepository = mavenAltDeploymentRepository;
        this.mavenPassword = mavenPassword;
        this.mavenUser = mavenUser;
        this.repositories = repositories;
        this.repositoriesToReleaseFrom = repositoriesToReleaseFrom;
        this.runDeploy = runDeploy;
        this.runTests = runTests;
        this.versionIncrementType = versionIncrementType;
    }

    public static ConfigBuilder builder(String baseDir,
                                        CredentialsProvider credentialsProvider,
                                        Set<String> repositories,
                                        Predicate<GA> dependenciesFilter) {
        CredentialsProvider.setDefault(credentialsProvider);
        return new ConfigBuilder().baseDir(baseDir).credentialsProvider(credentialsProvider).repositories(repositories).dependenciesFilter(dependenciesFilter);
    }
}
