package org.qubership.cloud.actions.maven;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.maven.model.*;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@CommandLine.Command(description = "maven bulk release cli")
@Slf4j
public class MavenBulkReleaseCli implements Runnable {

    @CommandLine.Option(names = {"--gitURL"}, required = true, description = "git host")
    private String gitURL;

    @CommandLine.Option(names = {"--gitUsername"}, required = true, description = "git username")
    private String gitUsername;

    @CommandLine.Option(names = {"--gitEmail"}, required = true, description = "git email")
    private String gitEmail;

    @CommandLine.Option(names = {"--gitPassword"}, required = true, description = "git password")
    private String gitPassword;

    @CommandLine.Option(names = {"--baseDir"}, required = true, description = "base directory to write result to")
    private String baseDir;

    @CommandLine.Option(names = {"--groupsPatterns"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of maven groupId pattern to use in dependency lookup")
    private Set<Pattern> groupsPatterns;

    @CommandLine.Option(names = {"--repositories"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of git urls to all repositories which depend on each other and can be bulk released")
    private Set<String> repositories;

    @CommandLine.Option(names = {"--repositoriesToReleaseFrom"}, split = "\\s*,\\s*",
            description = "comma seperated list of git urls which were changed and need to be released. Repositories which use them directly or indirectly will be released as well")
    private Set<String> repositoriesToReleaseFrom = Set.of();

    @CommandLine.Option(names = {"--gavs"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of GAVs to update dependencies from pom.xml files to")
    private Set<String> gavs;

    @CommandLine.Option(names = {"--skipTests"}, arity = "0", defaultValue = "false", description = "skip tests run by release:prepare mvn command")
    private boolean skipTests;

    @CommandLine.Option(names = {"--dryRun"}, arity = "0", defaultValue = "false", description = """
            if specified:
            1. only run release:prepare mvn command in each repository updating dependencies with versions from artifacts in dependent repositories
            if not specified:
            1. push git updates to origin
            2. deploy artifacts to distribution repository by release:perform mvn command
            """)
    private boolean dryRun;

    @CommandLine.Option(names = {"--mavenAltDeploymentRepository"},
            description = "altDeploymentRepository to pass to release:perform mvn command to override deploymentRepository to deploy artifacts to")
    private String mavenAltDeploymentRepository;

    @CommandLine.Option(names = {"--versionIncrementType"}, type = VersionIncrementType.class,
            description = "'altDeploymentRepository' to pass to release:perform mvn command to override deploymentRepository to deploy artifacts to")
    private VersionIncrementType versionIncrementType = VersionIncrementType.PATCH;

    @CommandLine.Option(names = {"--javaVersionToJavaHomeEnv"}, split = "\\s*,\\s*",
            description = "comma seperated list of javaVersion=JAVA_HOME mappings")
    private Map<String, String> javaVersionToJavaHomeEnv = Map.of();

    @CommandLine.Option(names = {"--mavenUser"}, description = "maven username to use to login to remote repository")
    private String mavenUser;

    @CommandLine.Option(names = {"--mavenPassword"}, description = "maven password to use to login to remote repository")
    private String mavenPassword;

    @CommandLine.Option(names = {"--summaryFile"}, description = "File path to save summary to")
    private String summaryFile;

    @CommandLine.Option(names = {"--resultOutputFile"}, description = "File path to save result GAVs to")
    private String resultOutputFile;

    @CommandLine.Option(names = {"--dependencyGraphFile"}, description = "File path to save dependencies graph in DOT format")
    private String dependencyGraphFile;

    @CommandLine.Option(names = {"--gavsResultFile"}, description = "File path to save dependencies graph in DOT format")
    private String gavsResultFile;

    public static void main(String... args) {
        CommandLine commandLine = new CommandLine(new MavenBulkReleaseCli());
        commandLine.registerConverter(VersionIncrementType.class, v -> VersionIncrementType.valueOf(v.toUpperCase()));
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            if (groupsPatterns.stream()
                    .filter(r -> !r.pattern().isBlank())
                    .toList()
                    .isEmpty()) {
                throw new IllegalArgumentException("--groupsPatterns property cannot be empty");
            }
            if (repositories.stream()
                    .filter(r -> !r.isBlank())
                    .toList().isEmpty()) {
                throw new IllegalArgumentException("--repositories property cannot be empty");
            }
            Predicate<GA> dependenciesFilter = ga -> groupsPatterns.stream().anyMatch(pattern -> pattern.matcher(ga.getGroupId()).matches());

            GitConfig gitConfig = GitConfig.builder().url(gitURL).username(gitUsername).email(gitEmail).password(gitPassword).build();
            gavs = this.gavs.stream().filter(gav -> !gav.isBlank()).collect(Collectors.toSet());
            repositoriesToReleaseFrom = repositoriesToReleaseFrom.stream().filter(r -> !r.isBlank()).collect(Collectors.toSet());

            Config config = Config.builder(baseDir, gitConfig, repositories, dependenciesFilter)
                    .repositoriesToReleaseFrom(repositoriesToReleaseFrom)
                    .versionIncrementType(versionIncrementType)
                    .mavenAltDeploymentRepository(mavenAltDeploymentRepository)
                    .javaVersionToJavaHomeEnv(javaVersionToJavaHomeEnv)
                    .mavenUser(mavenUser)
                    .mavenPassword(mavenPassword)
                    .gavs(gavs)
                    .skipTests(skipTests)
                    .dryRun(dryRun)
                    .build();
            Result result = new ReleaseRunner().release(config);
            if (summaryFile != null && !summaryFile.isBlank()) {
                // write summary
                try {
                    Path summaryPath = Paths.get(summaryFile);
                    String md = ReleaseSummary.md(result);
                    log.info("Writing to {} summary:\n{}", summaryPath, md);
                    Files.writeString(summaryPath, md, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write summary to file {}", summaryFile, e);
                }
            }
            if (resultOutputFile != null && !resultOutputFile.isBlank()) {
                // write the result
                try {
                    Path resultPath = Paths.get(resultOutputFile);
                    String gavsResult = ReleaseSummary.gavs(result);
                    log.info("Writing to {} result:\n{}", resultPath, gavsResult.replaceAll(",", "\n"));
                    Files.writeString(resultPath, String.format("result=%s", gavsResult), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write result to file {}", resultOutputFile, e);
                }
            }
            if (gavsResultFile != null && !gavsResultFile.isBlank()) {
                // write GAVs
                try {
                    Path resultPath = Paths.get(gavsResultFile);
                    String gavsResult = ReleaseSummary.gavs(result).replaceAll(",", "\n");
                    log.info("Writing to {} gavs:\n{}", resultPath, gavsResult);
                    Files.writeString(resultPath, gavsResult, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write GAVs to file {}", gavsResultFile, e);
                }
            }
            if (dependencyGraphFile != null && !dependencyGraphFile.isBlank()) {
                // write the dependency graph
                try {
                    Path resultPath = Paths.get(dependencyGraphFile);
                    String graph = ReleaseSummary.dependencyGraphDOT(result);
                    log.info("Writing to {} graph:\n{}", resultPath, graph);
                    Files.writeString(resultPath, graph, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.error("Failed to write dependency graph to file {}", dependencyGraphFile, e);
                }
            }
        } catch (Exception e) {
            if (summaryFile != null && !summaryFile.isBlank()) {
                // write summary
                try {
                    Path summaryPath = Paths.get(summaryFile);
                    String msg = String.format("Failed to perform maven bulk release: %s", e.getMessage());
                    log.info("Writing to {} summary:\n{}", summaryPath, msg);
                    Files.writeString(summaryPath, msg, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception we) {
                    log.error("Failed to write summary to file {}", summaryFile, we);
                }
            }
            throw new IllegalStateException("Failed to perform maven bulk release", e);
        }
    }
}
