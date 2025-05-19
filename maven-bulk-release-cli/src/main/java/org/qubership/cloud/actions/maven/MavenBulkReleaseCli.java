package org.qubership.cloud.actions.maven;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.qubership.cloud.actions.maven.model.*;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@CommandLine.Command(description = "maven bulk release cli")
@Slf4j
public class MavenBulkReleaseCli implements Runnable {

    @CommandLine.Option(names = {"--gitUsername"}, required = true, description = "git username")
    private String gitUsernameOption;

    @CommandLine.Option(names = {"--gitPassword"}, required = true, description = "git password")
    private String gitPasswordOption;

    @CommandLine.Option(names = {"--baseDir"}, required = true, description = "base directory to write result to")
    private String baseDirOption;

    @CommandLine.Option(names = {"--groupsPatterns"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of maven groupId pattern to use in dependency lookup")
    private Set<Pattern> groupsOption;

    @CommandLine.Option(names = {"--repositories"}, required = true, split = "\\s*,\\s*",
            description = "comma seperated list of git urls to all repositories which depend on each other and can be bulk released")
    private Set<String> repositoriesOption;

    @CommandLine.Option(names = {"--repositoriesToReleaseFrom"}, split = "\\s*,\\s*",
            description = "comma seperated list of git urls which were changed and need to be release along with repositories which use them directly or indirectly")
    private Set<String> repositoriesToReleaseFromOption;

    @CommandLine.Option(names = {"--runTests"}, defaultValue = "true", description = "run tests by release:prepare mvn command")
    private boolean runTestsOption;

    @CommandLine.Option(names = {"--dryRun"}, defaultValue = "false", description = """
            if present:
            1. only run release:prepare mvn command in each repository updating dependencies with versions from artifacts in dependent repositories
            if not specified:
            1. push git updates to origin
            2. deploy artifacts to distribution repository by release:perform mvn command
            """)
    private boolean dryRunOption;

    @CommandLine.Option(names = {"--mavenAltDeploymentRepository"},
            description = "altDeploymentRepository to pass to release:perform mvn command to override deploymentRepository to deploy artifacts to")
    private String mavenAltDeploymentRepositoryOption;

    @CommandLine.Option(names = {"--versionIncrementType"}, type = VersionIncrementType.class,
            description = "'altDeploymentRepository' to pass to release:perform mvn command to override deploymentRepository to deploy artifacts to")
    private VersionIncrementType versionIncrementTypeOption = VersionIncrementType.PATCH;

    @CommandLine.Option(names = {"--javaVersionToJavaHomeEnv"}, split = "\\s*,\\s*",
            description = "comma seperated list of javaVersion=JAVA_HOME mappings")
    private Map<String, String> javaVersionToJavaHomeEnvOption;

    @CommandLine.Option(names = {"--mavenUser"}, description = "maven username to use to login to remote repository")
    private String mavenUserOption;

    @CommandLine.Option(names = {"--mavenPassword"}, description = "maven password to use to login to remote repository")
    private String mavenPasswordOption;

    @CommandLine.Option(names = {"--summaryFile"}, description = "File path to save summary to")
    private String summaryFileOption;

    public static void main(String... args) {
        CommandLine commandLine = new CommandLine(new MavenBulkReleaseCli());
        commandLine.registerConverter(VersionIncrementType.class, v -> VersionIncrementType.valueOf(v.toUpperCase()));
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            if (groupsOption.isEmpty()) {
                throw new IllegalArgumentException("--groupsPatterns property cannot be empty");
            }
            if (repositoriesOption.isEmpty()) {
                throw new IllegalArgumentException("--repositories property cannot be empty");
            }
            Predicate<GA> dependenciesFilter = ga -> groupsOption.stream().anyMatch(pattern -> pattern.matcher(ga.getGroupId()).matches());
            Collection<String> gavs = Arrays.stream(System.getProperty("gavs", "").split("\\s*,\\s*"))
                    .filter(r -> !r.isBlank())
                    .toList();

            UsernamePasswordCredentialsProvider credentialsProvider = new UsernamePasswordCredentialsProvider(gitUsernameOption, gitPasswordOption);
            Config config = Config.builder(baseDirOption, credentialsProvider, repositoriesOption, dependenciesFilter)
                    .repositoriesToReleaseFrom(repositoriesToReleaseFromOption)
                    .versionIncrementType(versionIncrementTypeOption)
                    .mavenAltDeploymentRepository(mavenAltDeploymentRepositoryOption)
                    .javaVersionToJavaHomeEnv(javaVersionToJavaHomeEnvOption)
                    .mavenUser(mavenUserOption)
                    .mavenPassword(mavenPasswordOption)
                    .gavs(gavs)
                    .runTests(runTestsOption)
                    .runDeploy(!dryRunOption)
                    .build();
            ReleaseRunner releaseRunner = new ReleaseRunner();
            Result result = releaseRunner.release(config);
            if (summaryFileOption != null && !summaryFileOption.isBlank()) {
                // write summary
                Path summaryPath = Paths.get(summaryFileOption);
                String md = ReleaseSummary.md(result);
                log.info("Writing to {} summary:\n{}", summaryPath, md);
                Files.writeString(summaryPath, md, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to perform maven bulk release", e);
        }
    }
}
