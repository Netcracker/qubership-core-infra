package org.qubership.cloud.actions.maven.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@EqualsAndHashCode(callSuper = true)
@Data
public class GAV extends GA {
    static Pattern gavPattern = Pattern.compile("(.*):(.*):(.*)");
    String version;

    public GAV(String gav) {
        Matcher matcher = gavPattern.matcher(gav);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid gav: " + gav);
        this.groupId = matcher.group(1);
        this.artifactId = matcher.group(2);
        this.version = matcher.group(3);
    }

    public GAV(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    @Override
    public String toString() {
        return String.format("%s:%s:%s", groupId, artifactId, version);
    }
}
