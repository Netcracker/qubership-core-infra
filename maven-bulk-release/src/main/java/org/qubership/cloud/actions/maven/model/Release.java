package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.List;

@Data
public class Release {
    RepositoryInfo repository;
    String releaseVersion;
    List<GAV> gavs;
    String javaVersion;
}
