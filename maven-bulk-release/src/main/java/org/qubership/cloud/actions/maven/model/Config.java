package org.qubership.cloud.actions.maven.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
    boolean runTests;
    boolean runDeploy;
}
