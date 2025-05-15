package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Data
public class RepositoryInfo extends Repository {
    public static Pattern repositoryUrlPattern = Pattern.compile("https?://[^/]+/(.+)");

    String name;
    Set<GA> modules = new HashSet<>();
    Set<GAV> moduleDependencies = new HashSet<>();
    Set<RepositoryInfo> repoDependencies = new HashSet<>();

    public RepositoryInfo(String url) {
        this.url = url;
        Matcher matcher = repositoryUrlPattern.matcher(url);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid repository url: " + url);
        this.dir = matcher.group(1);
        this.name = this.dir.replaceAll("[/-]", "_");
    }

    public RepositoryInfo(String url, Set<RepositoryInfo> repoDependencies) {
        this(url);
        this.repoDependencies = repoDependencies.stream().map(ri -> new RepositoryInfo(ri.getUrl(), ri.getRepoDependencies())).collect(Collectors.toSet());
    }

    @JsonIgnore
    public Set<RepositoryInfo> getRepoDependenciesFlatSet() {
        Set<RepositoryInfo> result = new HashSet<>(repoDependencies);
        repoDependencies.forEach(ri -> result.addAll(ri.getRepoDependenciesFlatSet()));
        return result;
    }

}
