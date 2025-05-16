package org.qubership.cloud.actions.maven.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.function.Predicate;

@Data
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class Config {
    final String baseDir;
    // all repositories
    final List<String> repositories;
    final Predicate<GA> dependenciesFilter;
    Collection<String> dependencies = new ArrayList<>();
    VersionIncrementType versionIncrementType = VersionIncrementType.PATCH;
    Map<String, String> javaVersionToJavaHomeEnv = new HashMap<>();
    // particular repository(ies) to start release from (the rest of the tree will be calculated automatically)
    List<String> repositoriesToReleaseFrom = new ArrayList<>();
    String mavenUser;
    String mavenPassword;
    String mavenAltDeploymentRepository;
    boolean runTests;
    boolean runDeploy;
}
