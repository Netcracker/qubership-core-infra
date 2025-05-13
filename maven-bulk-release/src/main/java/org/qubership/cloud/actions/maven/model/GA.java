package org.qubership.cloud.actions.maven.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GA {
    String groupId;
    String artifactId;

    @Override
    public String toString() {
        return String.format("%s:%s", groupId, artifactId);
    }

}
