package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.List;

@Data
public class RepositoryRelease {
    RepositoryInfo repository;
    String releaseVersion;
    String tag;
    List<GAV> gavs;
    String javaVersion;
    boolean pushedToGit;
    boolean deployed;
    Exception exception;
}
