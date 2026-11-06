package com.netcracker.cloud.plugins.pins;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Applies the pins to version usages. Levels are not decided here: the dependency gate applies them.
 *
 * <ul>
 *     <li>{@code pin-exceeded}: the version is above the pin;</li>
 *     <li>{@code pin-mismatch}: the version does not match a pin that is not an exact version;</li>
 *     <li>{@code pin-conflict}: either of the above for a version managed by a BOM its own rule allows;</li>
 *     <li>{@code pin-lagging}: a version declared in the repository is below the pin;</li>
 *     <li>{@code feature-snapshot}: {@code X.Y.Z-<branch>-SNAPSHOT} of a branch snapshot deploy;</li>
 *     <li>{@code snapshot}: any SNAPSHOT that is not built here;</li>
 *     <li>{@code shared-property}: one property versions both pinned and unpinned artifacts.</li>
 * </ul>
 */
public final class PinsEvaluator {

    public static final String PIN_EXCEEDED = "pin-exceeded";
    public static final String PIN_MISMATCH = "pin-mismatch";
    public static final String PIN_LAGGING = "pin-lagging";
    public static final String PIN_CONFLICT = "pin-conflict";
    public static final String PIN_BOM_DRIFT = "pin-bom-drift";
    public static final String FEATURE_SNAPSHOT = "feature-snapshot";
    public static final String SNAPSHOT = "snapshot";
    public static final String SHARED_PROPERTY = "shared-property";

    /**
     * Checks {@link #evaluate} runs; {@code pin-bom-drift} belongs to {@link #lint}.
     */
    public static final List<String> CHECKS = List.of(PIN_EXCEEDED, PIN_MISMATCH, PIN_CONFLICT, PIN_LAGGING,
            FEATURE_SNAPSHOT, SNAPSHOT, SHARED_PROPERTY);

    // a BOM or parent failing one of these brings along its managed versions that fail the same check
    private static final Set<String> CAUSES = Set.of(PIN_EXCEEDED, PIN_MISMATCH, FEATURE_SNAPSHOT);

    // GAs whose pin is driven by infra dependencies/*-dependencies: exceeding them means infra must move first
    static final Map<String, String> PLATFORM_HINTS = Map.of(
            "org.springframework.boot:spring-boot-dependencies", "dependencies/spring-dependencies",
            "org.springframework.boot:spring-boot-starter-parent", "dependencies/spring-dependencies",
            "org.springframework.cloud:spring-cloud-dependencies", "dependencies/spring-dependencies",
            "io.quarkus.platform:quarkus-bom", "dependencies/quarkus-dependencies",
            "io.quarkus:quarkus-bom", "dependencies/quarkus-dependencies");

    // X.Y.Z-<branch>-SNAPSHOT as the branch snapshot deploy writes it (generic-maven-build.yaml, version-suffix):
    // the branch name reduced to [A-Za-z0-9-], at most 50 characters. Branches are named <type>/<name>, so the
    // suffix has at least two words, unlike qualifiers such as RC1, jre or alpha-1.
    private static final Pattern BRANCH_SNAPSHOT = Pattern.compile("\\d+\\.\\d+\\.\\d+-(?=[A-Za-z0-9-]{1,50}-SNAPSHOT$)"
            + "(?!(?i:alpha|beta|milestone|rc|cr)\\d*-)[A-Za-z0-9]+(-[A-Za-z0-9]+)+-SNAPSHOT");
    private static final int LISTED = 5;

    private final Pins pins;
    private Function<String, Collection<String>> managedBy = gav -> List.of();

    private final Map<String, Finding> findings = new LinkedHashMap<>();
    private final Map<PropertyKey, Map<String, PropertyUse>> propertyUsers = new TreeMap<>(
            Comparator.comparing(PropertyKey::file).thenComparing(PropertyKey::name));
    // g:a:v managed by a BOM its own rule allows; the same version in the dependency tree comes from there
    private final Map<String, String> allowedManaged = new HashMap<>();

    private record PropertyKey(String file, String name) {
    }

    private record PropertyUse(boolean pinned, Usage usage) {
    }

    /**
     * A violation of one check by one GA and version, with every place it occurs.
     */
    public static final class Finding {

        static final Comparator<Finding> ORDER = Comparator.comparing(Finding::check).thenComparing(Finding::ga)
                .thenComparing(Finding::version);

        private final String check;
        private final String ga;
        private final String version;
        private final String message;
        private final List<String> notes = new ArrayList<>();
        private final Map<String, Usage> usages = new LinkedHashMap<>();

        public Finding(String check, String ga, String version, String message) {
            this.check = check;
            this.ga = ga;
            this.version = version == null ? "" : version;
            this.message = message;
        }

        public static String key(String check, String ga, String version) {
            return check + "|" + ga + "|" + version;
        }

        public String key() {
            return key(check, ga, version);
        }

        public String check() {
            return check;
        }

        public String ga() {
            return ga;
        }

        public String version() {
            return version;
        }

        public String message() {
            return message;
        }

        public String note() {
            return String.join("; ", notes);
        }

        public Finding addNote(String note) {
            if (note != null && !note.isEmpty()) {
                notes.add(note);
            }
            return this;
        }

        public Finding addUsage(Usage usage) {
            usages.putIfAbsent(usage.toString(), usage);
            return this;
        }

        public List<Usage> usages() {
            return List.copyOf(usages.values());
        }

        /**
         * Only the resolved dependency tree shows it: no pom of the repository declares or manages this version.
         */
        public boolean transitive() {
            return !usages.isEmpty() && usages.values().stream().allMatch(u -> u.source() == Usage.Source.RESOLVED);
        }

        /**
         * For the build log: the finding, its note and up to {@code maxLocations} usages.
         */
        public String describe(int maxLocations) {
            String nl = System.lineSeparator();
            StringBuilder text = new StringBuilder(check + ": " + ga + ":" + version + " - " + message);
            if (!notes.isEmpty()) {
                text.append(nl).append("    note: ").append(note());
            }
            usages.values().stream().limit(maxLocations).forEach(u -> text.append(nl).append("    at ").append(u));
            if (usages.size() > maxLocations) {
                text.append(nl).append("    ... and ").append(usages.size() - maxLocations).append(" more");
            }
            return text.toString();
        }
    }

    public PinsEvaluator(Pins pins) {
        this.pins = pins;
    }

    /**
     * @param managedBy for a BOM or parent (g:a:v), the poms (g:a:v) its managed versions come from: itself, its
     *                  parents and the BOMs it imports, at any depth
     */
    public PinsEvaluator managedBy(Function<String, Collection<String>> managedBy) {
        this.managedBy = managedBy;
        return this;
    }

    /**
     * @param own GAs built by the checked repository: their versions are not pinned
     */
    public List<Finding> evaluate(Collection<Usage> usages, Set<String> own) {
        for (Usage u : usages) {
            String allowedBy = allowedBom(u);
            if (allowedBy != null) {
                allowedManaged.putIfAbsent(u.ga() + ":" + u.version(), allowedBy);
            }
        }
        usages.forEach(u -> check(u, own));
        sharedProperties();
        groupUnderCauses();
        return new ArrayList<>(findings.values());
    }

    private void check(Usage u, Set<String> own) {
        String v = u.version();
        if (v == null || v.isEmpty() || v.contains("${")) {
            return;
        }
        if (v.endsWith("-SNAPSHOT")) {
            if (BRANCH_SNAPSHOT.matcher(v).matches()) {
                add(FEATURE_SNAPSHOT, u, "feature branch SNAPSHOT: Renovate cannot offer the release, because Maven "
                        + "orders this version above X.Y.Z", null);
            } else if (u.kind() != Usage.Kind.PROJECT && !own.contains(u.ga())) {
                add(SNAPSHOT, u, "SNAPSHOT not built by this repository", null);
            }
            return;
        }
        if (own.contains(u.ga()) || u.kind() == Usage.Kind.PROJECT) {
            return;
        }
        Pins.Constraint c = pins.constraintFor(u.ga());
        if (u.property() != null && u.source() == Usage.Source.DECLARED && u.propertyFile() != null
                && !pins.isDisabled(u.ga())) {
            propertyUsers.computeIfAbsent(new PropertyKey(u.propertyFile(), u.property()), k -> new LinkedHashMap<>())
                    .put(u.ga(), new PropertyUse(c != null, u));
        }
        if (c == null) {
            return;
        }
        String hint = PLATFORM_HINTS.containsKey(u.ga()) ? "bump " + PLATFORM_HINTS.get(u.ga())
                + " in qubership-core-infra and regenerate renovate-core-config.json first, otherwise every pin of "
                + "this platform lags behind" : null;
        // a BOM that its own rule allows may manage versions above their pins: the config disagrees with itself,
        // and the repository cannot do anything about it
        String allowedBy = u.source() == Usage.Source.RESOLVED ? allowedManaged.get(u.ga() + ":" + v) : allowedBom(u);
        if (allowedBy != null) {
            hint = "managed by " + allowedBy + ", which its own rule allows: the pins of renovate-core-config.json "
                    + "disagree with each other";
        }
        if (c.allows(v)) {
            return;
        }
        int r = c.exact() != null ? Pins.compareVersions(v, c.exact()) : 0;
        if (r > 0) {
            add(allowedBy == null ? PIN_EXCEEDED : PIN_CONFLICT, u,
                    v + " is above the pin " + c.exact() + " (allowedVersions " + c.raw() + ")", hint);
        } else if (r < 0) {
            // BOM-managed and transitive versions legitimately sit below max(Spring, Quarkus)
            if (u.source() == Usage.Source.DECLARED) {
                add(PIN_LAGGING, u, v + " is below the pin " + c.exact() + ": behind the Spring/Quarkus BOMs", null);
            }
        } else if (c.exact() != null || u.source() == Usage.Source.DECLARED || c.above(v)) {
            // likewise for a pattern: a BOM-managed or transitive version may sit below what it allows
            add(allowedBy == null ? PIN_MISMATCH : PIN_CONFLICT, u,
                    v + " does not match allowedVersions " + c.raw(), hint);
        }
    }

    /**
     * The external BOM or parent an effective version comes from (g:a:v), if a pin rule allows that pom's version.
     */
    private String allowedBom(Usage u) {
        if (u.source() != Usage.Source.EFFECTIVE || u.via().isEmpty()) {
            return null;
        }
        String[] gav = u.via().getFirst().split(":");
        if (gav.length != 3) {
            return null;
        }
        Pins.Constraint c = pins.constraintFor(gav[0] + ":" + gav[1]);
        return c != null && c.allows(gav[2]) ? u.via().getFirst() + " (allowedVersions " + c.raw() + ")" : null;
    }

    private void add(String check, Usage u, String message, String note) {
        findings.computeIfAbsent(Finding.key(check, u.ga(), u.version()),
                k -> new Finding(check, u.ga(), u.version(), message).addNote(note)).addUsage(u);
    }

    private void sharedProperties() {
        propertyUsers.forEach((key, gas) -> {
            Map<Boolean, List<String>> byPinned = gas.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.partitioningBy(e -> e.getValue().pinned(),
                            Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
            if (byPinned.get(true).isEmpty() || byPinned.get(false).isEmpty()) {
                return;
            }
            Usage first = gas.values().iterator().next().usage();
            String property = "${" + key.name() + "}";
            Usage at = Usage.of(property, first.version(), Usage.Kind.PROPERTY, Usage.Source.DECLARED, key.file(),
                    first.propertyLine(), key.file());
            Finding f = new Finding(SHARED_PROPERTY, property, first.version(),
                    "property versions both pinned (" + String.join(", ", byPinned.get(true)) + ") and unpinned ("
                            + String.join(", ", byPinned.get(false))
                            + ") artifacts: a bump of the unpinned one bypasses the pin")
                    .addUsage(at);
            findings.put(f.key(), f);
        });
    }

    /**
     * A BOM or parent that fails a check brings along its managed versions that fail the same check: report them
     * with it, not one by one. Findings of other checks stay, whatever their level.
     */
    private void groupUnderCauses() {
        // check -> pom (g:a:v) that managed versions come from -> the failing BOM or parent
        Map<String, Map<String, Finding>> causes = new HashMap<>();
        for (Finding f : findings.values()) {
            if (!CAUSES.contains(f.check())) {
                continue;
            }
            for (Usage u : f.usages()) {
                if (u.source() == Usage.Source.DECLARED
                        && (u.kind() == Usage.Kind.IMPORT || u.kind() == Usage.Kind.PARENT)) {
                    Map<String, Finding> byPom = causes.computeIfAbsent(f.check(), k -> new HashMap<>());
                    String gav = f.ga() + ":" + f.version();
                    byPom.putIfAbsent(gav, f);
                    u.via().forEach(v -> byPom.putIfAbsent(v, f));
                    managedBy.apply(gav).forEach(v -> byPom.putIfAbsent(v, f));
                }
            }
        }
        if (causes.isEmpty()) {
            return;
        }
        Set<Finding> causeFindings = causes.values().stream().flatMap(m -> m.values().stream())
                .collect(Collectors.toSet());
        Map<Finding, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding f : List.copyOf(findings.values())) {
            if (!causes.containsKey(f.check()) || causeFindings.contains(f)) {
                continue;
            }
            Finding cause = causeOf(f, causes.get(f.check()));
            if (cause != null) {
                findings.remove(f.key());
                grouped.computeIfAbsent(cause, k -> new ArrayList<>()).add(f);
            }
        }
        grouped.forEach((cause, list) -> cause.addNote("it also brings " + list.size()
                + " managed version(s) that fail the same way: "
                + listed(list.stream().map(f -> f.ga() + ":" + f.version()).toList(), ", ", " and %d more")));
    }

    /**
     * The failing BOM or parent the version comes from, or null if it is only in the dependency tree or any usage has
     * another origin. The tree does not say where a version comes from: the same version a BOM manages comes from it.
     */
    private static Finding causeOf(Finding f, Map<String, Finding> causes) {
        Finding cause = null;
        for (Usage u : f.usages()) {
            if (u.source() == Usage.Source.RESOLVED) {
                continue;
            }
            Finding c = u.source() == Usage.Source.EFFECTIVE && !u.via().isEmpty() ? causes.get(u.via().getFirst())
                    : null;
            if (c == null) {
                return null;
            }
            cause = cause == null ? c : cause;
        }
        return cause;
    }

    /**
     * Inconsistencies inside the generated config itself: a BOM pinned below its own modules.
     */
    public static List<Finding> lint(Pins pins) {
        List<Finding> result = new ArrayList<>();
        Map<String, String> exact = pins.exactPins();
        exact.forEach((bom, bomVersion) -> {
            String group = bom.substring(0, bom.indexOf(':'));
            String artifact = bom.substring(bom.indexOf(':') + 1);
            if (!artifact.endsWith("-bom") && !artifact.endsWith("-dependencies")) {
                return;
            }
            Map<String, String> ahead = new TreeMap<>();
            exact.forEach((ga, v) -> {
                String g = ga.substring(0, ga.indexOf(':'));
                boolean sibling = !ga.equals(bom) && (g.equals(group) || g.startsWith(group + "."));
                if (sibling && Pins.compareVersions(v, bomVersion) > 0) {
                    ahead.put(ga, v);
                }
            });
            if (!ahead.isEmpty()) {
                String top = ahead.values().stream().max(Pins::compareVersions).orElseThrow();
                result.add(new Finding(PIN_BOM_DRIFT, bom, bomVersion, "BOM is pinned below its own modules ("
                        + listed(List.copyOf(ahead.keySet()), ", ", " ...") + " up to " + top
                        + "): importing the BOM at the pin downgrades them, and Renovate cannot raise the BOM to match"));
            }
        });
        return result;
    }

    /**
     * The first {@link #LISTED} values joined; {@code more} (a format taking the number left out) when there are more.
     */
    private static String listed(List<String> values, String delimiter, String more) {
        String head = String.join(delimiter, values.subList(0, Math.min(LISTED, values.size())));
        return values.size() > LISTED ? head + more.formatted(values.size() - LISTED) : head;
    }
}
