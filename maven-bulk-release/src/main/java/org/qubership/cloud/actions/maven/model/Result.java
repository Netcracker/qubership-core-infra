package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class Result {
    String dependenciesDot;
    List<RepositoryRelease> releases;
    Map<Integer, List<RepositoryInfo>> dependencyGraph;
}
