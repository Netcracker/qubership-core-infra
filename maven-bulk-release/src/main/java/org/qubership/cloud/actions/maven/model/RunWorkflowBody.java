package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;


@Data
public class RunWorkflowBody {
    String ref;
    @JsonProperty("event_type")
    String eventType;
    Map<String,Object> inputs;
}
