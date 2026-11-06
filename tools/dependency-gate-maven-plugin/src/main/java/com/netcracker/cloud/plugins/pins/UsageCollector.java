package com.netcracker.cloud.plugins.pins;

import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputLocationTracker;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Collects version usages of a set of Maven projects:
 * <ul>
 *     <li>declared: what the repository poms write, profiles included, with the property chain that sets each
 *     version, so that a report points at the line to change;</li>
 *     <li>effective: the effective model of each project, i.e. versions inherited from parents and managed by
 *     imported BOMs, with the external pom each one comes from.</li>
 * </ul>
 */
public final class UsageCollector {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    // poms Maven itself takes versions from (lifecycle bindings, super POM): not managed by the repository
    private static final List<String> MAVEN_BUILTIN = List.of("org.apache.maven:maven-core:",
            "org.apache.maven:maven-model-builder:");

    private final Path root;
    private final Properties userProperties;
    private final Map<Path, String> reactorFiles = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();
    private final Map<String, Usage> usages = new LinkedHashMap<>();
    private final Set<String> own = new TreeSet<>();

    /**
     * @param owner where the last property of the chain is defined, null if not in a pom of the project hierarchy
     */
    private record Resolved(String value, List<String> properties, Definition owner) {
    }

    /**
     * @param scope the model or the active profile of {@code project} that defines the property
     */
    private record Definition(MavenProject project, ModelBase scope) {
    }

    /**
     * @param tracker the model element, null for an annotation processor path
     * @param dom     the annotation processor path or its version element
     */
    private record Declaration(String groupId, String artifactId, String version, Usage.Kind kind,
                               InputLocationTracker tracker, Xpp3Dom dom) {

        Declaration(Dependency d, Usage.Kind kind) {
            this(d.getGroupId(), d.getArtifactId(), d.getVersion(), kind, d, null);
        }

        InputLocation versionLocation() {
            return tracker != null ? tracker.getLocation("version")
                    : dom.getInputLocation() instanceof InputLocation l ? l : null;
        }
    }

    /**
     * @param root           repository root: report paths are relative to it
     * @param userProperties {@code -D} properties of the build, used for versions such as {@code ${revision}}
     */
    public UsageCollector(Path root, Properties userProperties) {
        this.root = root.toAbsolutePath().normalize();
        this.userProperties = userProperties;
    }

    /**
     * @param projects all projects of the repository; their GAs are not checked against pins
     * @param checked  the projects to collect usages of (all of them unless some are excluded)
     */
    public UsageCollector collect(Collection<MavenProject> projects, Collection<MavenProject> checked) {
        for (MavenProject p : projects) {
            own.add(ga(p.getGroupId(), p.getArtifactId()));
            reactorFiles.put(normalize(p.getFile().toPath()), relative(p.getFile().toPath()));
        }
        for (MavenProject p : checked) {
            declared(p);
            effective(p);
        }
        return this;
    }

    public void add(Usage usage) {
        usages.putIfAbsent(usage.ga() + "|" + usage.version() + "|" + usage, usage);
    }

    public Collection<Usage> usages() {
        return usages.values();
    }

    public Set<String> own() {
        return own;
    }

    public String relative(Path file) {
        Path path = normalize(file);
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : path.toString();
    }

    private static Path normalize(Path file) {
        return file.toAbsolutePath().normalize();
    }

    private static String ga(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }

    private static boolean isMavenBuiltin(String modelId) {
        return modelId != null && MAVEN_BUILTIN.stream().anyMatch(modelId::startsWith);
    }

    // declared

    private void declared(MavenProject p) {
        Model raw = p.getOriginalModel() != null ? p.getOriginalModel() : p.getModel();
        String file = reactorFiles.get(normalize(p.getFile().toPath()));
        add(Usage.of(ga(p.getGroupId(), p.getArtifactId()), p.getVersion(), Usage.Kind.PROJECT,
                Usage.Source.DECLARED, file, line(located(raw, "version"), file, "<version>"), null));

        Parent parent = raw.getParent();
        if (parent != null && !own.contains(ga(parent.getGroupId(), parent.getArtifactId()))) {
            add(declaredUsage(file, ga(parent.getGroupId(), parent.getArtifactId()), resolve(parent.getVersion(), p),
                    Usage.Kind.PARENT, line(located(parent, "version"), file, "<parent>"), externalAncestors(p)));
        }

        Stream.concat(Stream.of(raw), raw.getProfiles().stream()).flatMap(scope -> declarations(scope).stream())
                .forEach(d -> {
                    String ga = ga(resolve(d.groupId(), p).value(), resolve(d.artifactId(), p).value());
                    InputLocation at = d.tracker() != null ? located(d.tracker(), "version") : d.versionLocation();
                    int line = line(at, file, "<artifactId>" + d.artifactId() + "<");
                    add(declaredUsage(file, ga, resolve(d.version(), p), d.kind(), line, List.of()));
                });
    }

    private Usage declaredUsage(String file, String ga, Resolved v, Usage.Kind kind, int line, List<String> via) {
        String propertyFile = null;
        int propertyLine = 0;
        if (!v.properties().isEmpty() && v.owner() != null) {
            propertyFile = reactorFiles.get(normalize(v.owner().project().getFile().toPath()));
            if (propertyFile != null) {
                String property = v.properties().getLast();
                InputLocation properties = v.owner().scope().getLocation("properties");
                propertyLine = line(properties != null ? properties.getLocation(property) : null, propertyFile,
                        "<" + property + ">");
            }
        }
        return new Usage(ga, v.value(), kind, Usage.Source.DECLARED, file, line, v.properties(), propertyFile,
                propertyLine, via, file);
    }

    private List<String> externalAncestors(MavenProject p) {
        List<String> result = new ArrayList<>();
        for (MavenProject cur = p.getParent(); cur != null; cur = cur.getParent()) {
            if (!own.contains(ga(cur.getGroupId(), cur.getArtifactId()))) {
                result.add(cur.getGroupId() + ":" + cur.getArtifactId() + ":" + cur.getVersion());
            }
        }
        return result;
    }

    // effective

    private void effective(MavenProject p) {
        String module = reactorFiles.get(normalize(p.getFile().toPath()));
        for (Declaration d : declarations(p.getModel())) {
            String ga = ga(d.groupId(), d.artifactId());
            InputLocation location = d.versionLocation();
            if (location == null || location.getSource() == null) {
                add(Usage.of(ga, d.version(), d.kind(), Usage.Source.EFFECTIVE,
                        "effective model of " + p.getGroupId() + ":" + p.getArtifactId(), 0, module));
                continue;
            }
            String modelId = location.getSource().getModelId();
            if (isMavenBuiltin(modelId)) {
                continue;
            }
            String path = location.getSource().getLocation();
            String file = path == null ? null : reactorFiles.get(normalize(Path.of(path)));
            String from = file != null ? file : modelId != null ? modelId : path;
            add(new Usage(ga, d.version(), d.kind(), Usage.Source.EFFECTIVE, from, location.getLineNumber(),
                    List.of(), null, 0, file != null ? List.of() : List.of(from), module));
        }
    }

    /**
     * The poms (g:a:v) the managed versions of an effective model come from: the pom itself, its parents and the BOMs
     * it imports, at any depth.
     */
    public static Set<String> managedSources(Model effective) {
        Stream<? extends InputLocationTracker> managed = Stream.concat(
                effective.getDependencyManagement() != null
                        ? effective.getDependencyManagement().getDependencies().stream() : Stream.empty(),
                effective.getBuild() != null && effective.getBuild().getPluginManagement() != null
                        ? effective.getBuild().getPluginManagement().getPlugins().stream() : Stream.empty());
        return managed.map(tracker -> tracker.getLocation("version"))
                .map(location -> location != null && location.getSource() != null
                        ? location.getSource().getModelId() : null)
                .filter(modelId -> modelId != null && !isMavenBuiltin(modelId))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    // model traversal

    /**
     * What a model or a profile declares with a version: dependencies, dependency management, plugins with plugin
     * management, plugin dependencies, annotation processor paths and extensions.
     */
    private static List<Declaration> declarations(ModelBase scope) {
        List<Declaration> out = new ArrayList<>();
        scope.getDependencies().forEach(d -> out.add(new Declaration(d, Usage.Kind.DEPENDENCY)));
        if (scope.getDependencyManagement() != null) {
            scope.getDependencyManagement().getDependencies().forEach(d -> out.add(new Declaration(d,
                    "import".equals(d.getScope()) ? Usage.Kind.IMPORT : Usage.Kind.MANAGED)));
        }
        BuildBase build = switch (scope) {
            case Model m -> m.getBuild();
            case Profile profile -> profile.getBuild();
            default -> null;
        };
        if (build != null) {
            List<Plugin> plugins = new ArrayList<>(build.getPlugins());
            if (build.getPluginManagement() != null) {
                plugins.addAll(build.getPluginManagement().getPlugins());
            }
            for (Plugin plugin : plugins) {
                out.add(new Declaration(plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion(),
                        Usage.Kind.PLUGIN, plugin, null));
                plugin.getDependencies().forEach(d -> out.add(new Declaration(d, Usage.Kind.PLUGIN_DEPENDENCY)));
                annotationProcessors(plugin.getConfiguration(), out);
                plugin.getExecutions().forEach(execution -> annotationProcessors(execution.getConfiguration(), out));
            }
        }
        if (build instanceof Build b) {
            b.getExtensions().forEach(e -> out.add(new Declaration(e.getGroupId(), e.getArtifactId(), e.getVersion(),
                    Usage.Kind.EXTENSION, e, null)));
        }
        return out.stream().filter(d -> d.artifactId() != null && d.version() != null).toList();
    }

    private static void annotationProcessors(Object configuration, List<Declaration> out) {
        Xpp3Dom paths = configuration instanceof Xpp3Dom dom ? dom.getChild("annotationProcessorPaths") : null;
        if (paths == null) {
            return;
        }
        for (Xpp3Dom path : paths.getChildren("path")) {
            Xpp3Dom version = path.getChild("version");
            out.add(new Declaration(value(path, "groupId"), value(path, "artifactId"), value(path, "version"),
                    Usage.Kind.ANNOTATION_PROCESSOR, null, version != null ? version : path));
        }
    }

    private static String value(Xpp3Dom dom, String child) {
        return dom.getChild(child) != null ? dom.getChild(child).getValue() : null;
    }

    // properties

    /**
     * Interpolates {@code ${...}} in Maven's order: project values, user properties ({@code -D}), then the properties
     * of the project, its active profiles and its parents. Remembers the property chain and where the last property
     * of it is defined.
     */
    private Resolved resolve(String value, MavenProject p) {
        if (value == null) {
            return new Resolved(null, List.of(), null);
        }
        List<String> chain = new ArrayList<>();
        Definition owner = null;
        for (int i = 0; i < 20; i++) {
            Matcher m = PROPERTY.matcher(value);
            if (!m.find()) {
                break;
            }
            String name = m.group(1);
            Definition definition = null;
            String replacement = builtin(name, p);
            if (replacement == null) {
                replacement = userProperties.getProperty(name);
            }
            if (replacement == null) {
                definition = definition(name, p);
                // not defined in the hierarchy, e.g. inherited from an external parent: the effective value
                replacement = definition != null ? definition.scope().getProperties().getProperty(name)
                        : p.getProperties().getProperty(name);
            }
            if (replacement == null) {
                break;
            }
            chain.add(name);
            owner = definition;
            value = value.substring(0, m.start()) + replacement + value.substring(m.end());
        }
        return new Resolved(value, chain, owner);
    }

    /**
     * The nearest pom of the hierarchy that defines the property. In a pom an active profile overrides the model, and
     * of several active profiles the last one wins: Maven injects them in order.
     */
    private static Definition definition(String name, MavenProject p) {
        for (MavenProject cur = p; cur != null; cur = cur.getParent()) {
            Model raw = cur.getOriginalModel();
            if (raw == null) {
                continue;
            }
            Set<String> active = cur.getActiveProfiles().stream().map(Profile::getId).collect(Collectors.toSet());
            List<Profile> profiles = raw.getProfiles().stream().filter(profile -> active.contains(profile.getId()))
                    .toList();
            for (Profile profile : profiles.reversed()) {
                if (profile.getProperties().containsKey(name)) {
                    return new Definition(cur, profile);
                }
            }
            if (raw.getProperties().containsKey(name)) {
                return new Definition(cur, raw);
            }
        }
        return null;
    }

    private static String builtin(String name, MavenProject p) {
        return switch (name) {
            case "project.version", "pom.version", "version" -> p.getVersion();
            case "project.groupId", "pom.groupId" -> p.getGroupId();
            case "project.artifactId", "pom.artifactId" -> p.getArtifactId();
            case "project.parent.version", "parent.version" -> p.getParent() != null ? p.getParent().getVersion()
                    : null;
            default -> null;
        };
    }

    // line numbers: tracked input locations, the pom text as a fallback

    /**
     * The location of a field, or of the element itself when the field has none.
     */
    private static InputLocation located(InputLocationTracker tracker, String field) {
        InputLocation location = tracker.getLocation(field);
        return location != null ? location : tracker.getLocation("");
    }

    private int line(InputLocation location, String file, String needle) {
        return location != null && location.getLineNumber() > 0 ? location.getLineNumber() : find(file, needle);
    }

    private int find(String file, String needle) {
        if (file == null) {
            return 0;
        }
        String text = texts.computeIfAbsent(file, f -> {
            try {
                return Files.readString(root.resolve(f), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        int i = text.indexOf(needle);
        return i < 0 ? 0 : (int) text.chars().limit(i).filter(c -> c == '\n').count() + 1;
    }
}
