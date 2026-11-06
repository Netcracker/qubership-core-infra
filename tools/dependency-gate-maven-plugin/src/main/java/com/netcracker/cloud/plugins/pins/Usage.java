package com.netcracker.cloud.plugins.pins;

import java.util.List;
import java.util.Locale;

/**
 * One place where a version of a GA is set or used.
 *
 * @param file         repository-relative pom path, or a description of where the version comes from
 * @param properties   property chain that produced the version, outermost first
 * @param propertyFile repository-relative pom defining the last property of the chain, null if not in the repository
 * @param via          coordinates (g:a:v) of the external poms the version comes from, nearest first
 * @param module       repository-relative pom of the project the usage belongs to, null if unknown
 */
public record Usage(String ga, String version, Kind kind, Source source, String file, int line,
                    List<String> properties, String propertyFile, int propertyLine, List<String> via, String module) {

    public enum Kind {
        PROJECT, PARENT, DEPENDENCY, MANAGED, IMPORT, PLUGIN, PLUGIN_DEPENDENCY, ANNOTATION_PROCESSOR, EXTENSION,
        RESOLVED, PROPERTY
    }

    public enum Source {
        /** written in a pom of the checked repository, properties resolved */
        DECLARED,
        /** the effective model: inherited and BOM-managed versions */
        EFFECTIVE,
        /** the resolved dependency tree, transitive dependencies included */
        RESOLVED
    }

    public Usage {
        properties = properties == null ? List.of() : List.copyOf(properties);
        via = via == null ? List.of() : List.copyOf(via);
    }

    public static Usage of(String ga, String version, Kind kind, Source source, String file, int line,
                           String module) {
        return new Usage(ga, version, kind, source, file, line, List.of(), null, 0, List.of(), module);
    }

    /**
     * The last property of the chain, the one whose definition sets the version; null without properties.
     */
    public String property() {
        return properties.isEmpty() ? null : properties.getLast();
    }

    public String where() {
        String at = line > 0 ? file + ":" + line : file;
        return propertyFile != null ? propertyFile + ":" + propertyLine + " (${" + property() + "}) <- " + at : at;
    }

    /**
     * Whether {@link #file()} is a pom of the repository rather than an external pom or the dependency tree.
     */
    public boolean inRepository() {
        return source == Source.DECLARED || source == Source.EFFECTIVE && via.isEmpty() && line > 0;
    }

    /**
     * The line to change: the property definition if there is one, otherwise the declaration.
     */
    public String changeFile() {
        return propertyFile != null ? propertyFile : file;
    }

    public int changeLine() {
        return propertyFile != null ? propertyLine : line;
    }

    @Override
    public String toString() {
        return where() + " [" + label(kind) + ", " + label(source) + "]";
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
