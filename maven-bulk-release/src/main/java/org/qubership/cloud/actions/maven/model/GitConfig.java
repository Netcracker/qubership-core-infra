package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@ToString(exclude = "password")
@Data
@Builder
public class GitConfig {
    String username;
    String email;
    @JsonIgnore
    String password;
    String url;
}
