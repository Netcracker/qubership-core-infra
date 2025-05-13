package org.qubership.cloud.actions.maven.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Repository {
    String url;
    String dir;
}
