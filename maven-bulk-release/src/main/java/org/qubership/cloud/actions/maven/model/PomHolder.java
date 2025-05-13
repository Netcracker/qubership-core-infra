package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Data
public class PomHolder {
    Path path;
    PomHolder parent;
    JsonNode pomNode;
    String pom;
    GA ga;

    public List<PomHolder> getParentsFlatList() {
        List<PomHolder> parents = new ArrayList<>();
        if (this.parent != null) {
            parents.add(this.parent);
            parents.addAll(this.parent.getParentsFlatList());
        }
        return parents;
    }

    @Override
    public String toString() {
        return String.format("%s", pomNode.get("artifactId").asText());
    }

}
