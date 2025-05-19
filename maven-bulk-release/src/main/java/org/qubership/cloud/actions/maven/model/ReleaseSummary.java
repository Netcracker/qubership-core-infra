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
                    String tagUrl = r.isPushedToGit() ?
                            String.format("%s/tree/%s", r.getRepository().getUrl(), r.getTag()) :
                            r.getRepository().getUrl();
                    String gavs = r.getGavs().stream().map(GAV::toString).collect(Collectors.joining("\n"));
                    String link = String.format("[%s](%s)", r.getRepository().getUrl(), tagUrl);
                    return String.format(repositoryPart, link, gavs);
                }).toList());
        return String.format("""
                ### Release Summary%s
                %s
                """, result.isDryRun()? " [DRY RUN]" : "", releasedRepositoriesGavs);
    }
}
