package org.qubership.cloud.actions.maven.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.function.Predicate;

@Data
@RequiredArgsConstructor
@AllArgsConstructor
public class Config {
    final String baseDir;
    final List<String> repositories;
    final Predicate<GA> dependenciesFilter;
    Collection<String> dependencies = new ArrayList<>();
    VersionIncrementType versionIncrementType = VersionIncrementType.PATCH;
    Map<String, String> javaVersionToJavaHomeEnv = new HashMap<>();
    boolean runTests;
    boolean runDeploy;
}
