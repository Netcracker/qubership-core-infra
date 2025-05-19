package org.qubership.cloud.actions.maven.model;

import java.util.stream.Collectors;

public class ReleaseSummary {

    public  static String md(Result result) {
        String releasedRepositoriesGavs = String.join("\n", result.getReleases().stream()
                .map(r -> {
                    // language=md
                    String repositoryPart = """
                            #### %s
                            ```
                            %s
                            ```
                            """;
                    String tagUrl = String.format("%s/tree/%s", r.getRepository().getUrl(), r.getTag());
                    String gavs = r.getGavs().stream().map(GAV::toString).collect(Collectors.joining("\n"));
                    return String.format(repositoryPart, tagUrl, gavs);
                }).toList());
        return String.format("""
                ### Release Summary
                %s
                """, releasedRepositoriesGavs);
    }
}
