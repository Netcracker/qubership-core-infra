package org.qubership.cloud.actions.maven.model;

import lombok.Data;

import java.util.List;

@Data
public class Result {
    String dependenciesDot;
    List<Release> releases;
}
