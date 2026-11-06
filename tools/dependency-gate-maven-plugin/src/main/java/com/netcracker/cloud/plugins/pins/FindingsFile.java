package com.netcracker.cloud.plugins.pins;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.netcracker.cloud.plugins.pins.PinsEvaluator.Finding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * findings.v1 of the dependency gate (qubership-core-infra, {@code tools/dependency-gate/schemas/findings.v1.json}):
 * what this adapter found in one revision. Levels, exceptions and the baseline are applied by the gate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindingsFile(String schema, Adapter adapter, String revision, List<Item> findings) {

    public static final String SCHEMA = "dependency-gate-findings/v1";
    public static final String ADAPTER = "maven-pins";
    public static final List<String> REVISIONS = List.of("head", "base");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Adapter(String name, String ecosystem, String tool, String status, String reason,
                          List<String> checks) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Item(String check, @JsonProperty("package") String pkg, String version, String message, String note,
                       List<Location> locations, Boolean transitive) {

        static Item of(Finding f) {
            return new Item(f.check(), f.ga(), f.version(), f.message(), f.note(), locationsOf(f),
                    f.transitive() ? Boolean.TRUE : null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(String file, Integer line) {
    }

    /**
     * @param reason what the gate should show although the check finished, such as an incomplete dependency tree;
     *               null if nothing
     */
    public static FindingsFile ok(String tool, String revision, List<Finding> findings, String reason) {
        List<Item> items = findings.stream().sorted(Finding.ORDER).map(Item::of).toList();
        return new FindingsFile(SCHEMA, new Adapter(ADAPTER, "maven", tool, "ok", reason, PinsEvaluator.CHECKS),
                revision, items);
    }

    public static FindingsFile error(String tool, String revision, String reason) {
        return new FindingsFile(SCHEMA, new Adapter(ADAPTER, "maven", tool, "error", reason, List.of()), revision,
                List.of());
    }

    public void write(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), this);
    }

    /**
     * Repository poms to change first; usages outside the repository (external BOMs, the dependency tree) point at
     * the pom of the module they belong to.
     */
    private static List<Location> locationsOf(Finding f) {
        Set<Location> result = new LinkedHashSet<>();
        f.usages().stream().filter(Usage::inRepository)
                .forEach(u -> result.add(new Location(u.changeFile(), u.changeLine() > 0 ? u.changeLine() : null)));
        f.usages().stream().filter(u -> !u.inRepository() && u.module() != null).map(Usage::module)
                .filter(module -> result.stream().noneMatch(l -> l.file().equals(module)))
                .forEach(module -> result.add(new Location(module, null)));
        return List.copyOf(result);
    }
}
