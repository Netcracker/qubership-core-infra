package org.qubership.cloud.actions.maven.model;

import lombok.Data;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
public class PomHolder {

    static Pattern dependencyPattern = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
    static Pattern groupIdPattern = Pattern.compile("<groupId>(.+)</groupId>");
    static Pattern artifactIdPattern = Pattern.compile("<artifactId>(.+)</artifactId>");
    static Pattern versionPattern = Pattern.compile("<version>(.+)</version>");
    static Pattern referencePattern = Pattern.compile("\\$\\{(.+?)}");

    Path path;
    PomHolder parent;
    Model model;
    String pom;

    public PomHolder(String pom, Path path) {
        this.path = path;
        this.setPom(pom);
    }

    public void setPom(String pom) {
        try {
            this.pom = pom;
            Model model = new MavenXpp3Reader().read(new ByteArrayInputStream(pom.getBytes()));
            this.setModel(model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<PomHolder> getParentsFlatList() {
        List<PomHolder> parents = new ArrayList<>();
        if (this.parent != null) {
            parents.add(this.parent);
            parents.addAll(this.parent.getParentsFlatList());
        }
        return parents;
    }

    public String getGroupId() {
        return Optional.ofNullable(model.getGroupId()).orElseGet(() -> model.getParent().getGroupId());
    }

    public String getArtifactId() {
        return model.getArtifactId();
    }

    public String getVersion() {
        return Optional.ofNullable(model.getVersion()).orElseGet(() -> model.getParent().getVersion());
    }

    public String autoResolvePropReference(String value) {
        if (value == null) return null;
        Matcher referenceMatcher = referencePattern.matcher(value);
        if (referenceMatcher.find()) {
            // find reference among properties
            String prop = referenceMatcher.group(1);
            Map<String, String> properties = getProperties();
            String valueFromProperty = properties.get(prop);
            if (valueFromProperty != null) {
                return autoResolvePropReference(valueFromProperty, properties);
            } else {
                return value;
            }
        }
        return value;
    }

    public String autoResolvePropReference(String value, Map<String, String> properties) {
        if (value == null) return null;
        Matcher referenceMatcher = referencePattern.matcher(value);
        if (referenceMatcher.find()) {
            // find reference among properties
            String prop = referenceMatcher.group(1);
            String valueFromProperty = properties.get(prop);
            if (valueFromProperty != null) {
                return autoResolvePropReference(valueFromProperty, properties);
            } else {
                return value;
            }
        }
        return value;
    }


    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        PomHolder ph = this;
        while (ph != null) {
            Model model = ph.getModel();
            properties.putAll(model.getProperties().entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String)
                    .collect(Collectors.toMap(entry -> (String) entry.getKey(), entry -> (String) entry.getValue())));
            ph = ph.getParent();
        }
        return properties.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    String value = entry.getValue();
                    return autoResolvePropReference(value, properties);
                }));
    }

    public void updateProperty(String name, String version) {
        Pattern propertiesPattern = Pattern.compile("(?s)<properties>(.+?)</properties>");
        Pattern propertyPattern = Pattern.compile(MessageFormat.format("<{0}>(.+)</{0}>", name));
        String pomContent = this.getPom();
        Matcher matcher = propertiesPattern.matcher(pomContent);
        while (matcher.find()) {
            String properties = matcher.group();
            String propertiesContent = matcher.group(1);
            Matcher propMatcher = propertyPattern.matcher(propertiesContent);
            String newVersionTag = MessageFormat.format("<{0}>{1}</{0}>", name, version);
            while (propMatcher.find()) {
                String oldVersionTag = propMatcher.group();
                pomContent = pomContent.replace(properties, properties.replace(oldVersionTag, newVersionTag));
            }
        }
        setPom(pomContent);
    }

    public void updateVersionInDependency(GAV gav) {
        String pomContent = pom;
        Matcher matcher = dependencyPattern.matcher(pomContent);

        while (matcher.find()) {
            String dependency = matcher.group();
            String dependencyContent = matcher.group(1);
            Matcher groupIdMatcher = groupIdPattern.matcher(dependencyContent);
            Matcher artifactIdMatcher = artifactIdPattern.matcher(dependencyContent);
            Matcher versionMatcher = versionPattern.matcher(dependencyContent);
            if (groupIdMatcher.find() && artifactIdMatcher.find() && versionMatcher.find()) {
                String groupId = autoResolvePropReference(groupIdMatcher.group(1));
                String artifactId = autoResolvePropReference(artifactIdMatcher.group(1));
                String version = versionMatcher.group(1);
                if (groupId.equals(gav.getGroupId()) && artifactId.equals(gav.getArtifactId())) {
                    pomContent = pomContent.replace(dependency, dependency.replace(version, gav.getVersion()));
                }
            }
        }
        setPom(pomContent);
    }

    @Override
    public String toString() {
        return String.format("%s", model.getArtifactId());
    }

}
