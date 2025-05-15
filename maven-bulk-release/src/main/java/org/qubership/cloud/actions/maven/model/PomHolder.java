package org.qubership.cloud.actions.maven.model;

import lombok.Data;
import org.apache.maven.model.Model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
public class PomHolder {
    Path path;
    PomHolder parent;
    Model model;
    String pom;

    public List<PomHolder> getParentsFlatList() {
        List<PomHolder> parents = new ArrayList<>();
        if (this.parent != null) {
            parents.add(this.parent);
            parents.addAll(this.parent.getParentsFlatList());
        }
        return parents;
    }

    public String getGroupId() {
        return Optional.ofNullable(model.getGroupId()).orElseGet(() -> model.getParent().getGroupId());
    }

    public String getArtifactId() {
        return model.getArtifactId();
    }

    public String getVersion() {
        return Optional.ofNullable(model.getVersion()).orElseGet(() -> model.getParent().getVersion());
    }

    @Override
    public String toString() {
        return String.format("%s", model.getArtifactId());
    }

}
