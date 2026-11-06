package com.netcracker.cloud.plugins.pins;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Version pins of renovate-core-config.json: package rules that list exact GAs and an allowedVersions pattern
 * (generated as max(Spring Boot BOM, Quarkus BOM)). Pattern-only rules, such as the global SNAPSHOT ban, are not
 * pins; rules with keys this class does not evaluate (see SUPPORTED_KEYS) are skipped.
 */
public final class Pins {

    /**
     * Package rule keys that are evaluated for a GA or do not change which versions Renovate allows. Any other key
     * ({@code match*}, {@code exclude*}, {@code versioning}, ...) may narrow or change the rule, so the rule is skipped
     * rather than read wider than Renovate applies it.
     */
    private static final Set<String> SUPPORTED_KEYS = Set.of("matchPackageNames", "matchManagers", "matchDatasources",
            "allowedVersions", "enabled", "description", "groupName", "groupSlug", "addLabels", "labels",
            "prBodyNotes", "registryUrls", "separateMinorPatch");

    private static final Pattern JS_REGEX = Pattern.compile("/(.*)/([a-z]*)", Pattern.DOTALL);

    private final List<Rule> rules = new ArrayList<>();
    private final List<Rule> disabled = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private final Map<String, Optional<Constraint>> cache = new HashMap<>();

    /**
     * An allowedVersions value of a Renovate package rule.
     *
     * @param raw     the value as written in the config
     * @param exact   the pinned version when the value is {@code /^X$/}, otherwise null
     * @param prefix  for another positive pattern, the leading numbers every allowed version starts with, such as
     *                {@code 2.28} for {@code /^2\.28\./}; otherwise null
     * @param regex   the pattern without the leading {@code !}
     * @param negated {@code !/regex/}: versions matching the pattern are not allowed
     */
    public record Constraint(String raw, String exact, String prefix, Pattern regex, boolean negated) {

        private static final Pattern EXACT = Pattern.compile("/\\^((?:[A-Za-z0-9_+~-]|\\\\?\\.)+)\\$/");
        // numbers each followed by an escaped dot: ^2\.28 alone would also allow 2.280
        private static final Pattern PREFIX = Pattern.compile("\\^((?:\\d+\\\\\\.)+)");
        private static final Pattern NUMBERS = Pattern.compile("\\d+(?:\\.\\d+)*");

        /**
         * @return null when the value is not a regex (Renovate semver ranges are not used by the pin generator)
         */
        static Constraint parse(String raw) {
            boolean negated = raw.startsWith("!");
            String body = negated ? raw.substring(1) : raw;
            Pattern regex = Pins.regex(body);
            if (regex == null) {
                return null;
            }
            Matcher m = EXACT.matcher(body);
            String exact = !negated && m.matches() ? m.group(1).replace("\\.", ".") : null;
            Matcher p = PREFIX.matcher(regex.pattern());
            String prefix = !negated && exact == null && regex.pattern().indexOf('|') < 0 && p.lookingAt()
                    ? p.group(1).replace("\\.", ".").replaceFirst("\\.$", "") : null;
            return new Constraint(raw, exact, prefix, regex, negated);
        }

        /**
         * The regex decides, as in Renovate: {@code /^5.30.0$/} allows neither {@code 5.30} nor {@code 5.30.0.0}.
         */
        public boolean allows(String version) {
            return negated != regex.matcher(version).find();
        }

        /**
         * Above every version a pattern allows: its leading numbers are above the {@link #prefix}, such as
         * {@code 2.29.0-RC1} for {@code /^2\.28\./}. False when that cannot be told, as for a negated pattern.
         */
        public boolean above(String version) {
            Matcher m = NUMBERS.matcher(version);
            if (prefix == null || !m.lookingAt()) {
                return false;
            }
            String[] numbers = m.group().split("\\.");
            int count = Math.min(numbers.length, prefix.split("\\.").length);
            return compareVersions(String.join(".", Arrays.copyOf(numbers, count)), prefix) > 0;
        }
    }

    private record Rule(Set<String> exact, List<Predicate<String>> positives, List<Predicate<String>> negatives,
                        Constraint constraint) {

        boolean matches(String ga) {
            boolean hit = exact.contains(ga) || positives.stream().anyMatch(p -> p.test(ga));
            return hit && negatives.stream().noneMatch(n -> n.test(ga));
        }
    }

    public Pins(JsonNode config) {
        JsonNode packageRules = config.path("packageRules");
        for (int i = 0; i < packageRules.size(); i++) {
            try {
                add(i, packageRules.get(i));
            } catch (PatternSyntaxException e) {
                skipped.add("packageRules[" + i + "]: " + e.getDescription());
            }
        }
    }

    /**
     * Maven version order ({@code ComparableVersion}), which Renovate's maven versioning follows too.
     */
    public static int compareVersions(String a, String b) {
        return new ComparableVersion(a).compareTo(new ComparableVersion(b));
    }

    private void add(int index, JsonNode rule) {
        Set<String> unsupported = new TreeSet<>();
        rule.fieldNames().forEachRemaining(unsupported::add);
        unsupported.removeAll(SUPPORTED_KEYS);
        List<String> names = strings(rule.path("matchPackageNames"));
        if (rule.path("enabled").isBoolean() && !rule.path("enabled").asBoolean() && !names.isEmpty()
                && unsupported.isEmpty()) {
            disabled.add(new Rule(Set.of(), positives(names, Set.of()), negatives(names), null));
            return;
        }
        if (!rule.has("allowedVersions")
                || !appliesToMaven(rule.path("matchManagers")) || !appliesToMaven(rule.path("matchDatasources"))) {
            return;
        }
        if (!unsupported.isEmpty()) {
            skipped.add("packageRules[" + index + "]: unsupported keys " + unsupported);
            return;
        }
        Set<String> exact = new TreeSet<>(names.stream().filter(Pins::isExactName).toList());
        if (exact.isEmpty()) {
            return;
        }
        String allowed = rule.path("allowedVersions").asText();
        Constraint constraint = Constraint.parse(allowed);
        if (constraint == null) {
            skipped.add("packageRules[" + index + "]: allowedVersions is not a regex: " + allowed);
            return;
        }
        rules.add(new Rule(exact, positives(names, exact), negatives(names), constraint));
    }

    private static boolean appliesToMaven(JsonNode list) {
        return list.isMissingNode() || list.isEmpty() || strings(list).contains("maven");
    }

    private static List<Predicate<String>> positives(List<String> names, Set<String> exact) {
        return names.stream().filter(n -> !n.startsWith("!") && !exact.contains(n)).map(Pins::matcher).toList();
    }

    private static List<Predicate<String>> negatives(List<String> names) {
        return names.stream().filter(n -> n.startsWith("!")).map(n -> matcher(n.substring(1))).toList();
    }

    private static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        node.forEach(n -> result.add(n.asText()));
        return result;
    }

    /**
     * Renovate merges package rules in order: the last matching allowedVersions wins.
     *
     * @return null if the GA is not pinned
     */
    public Constraint constraintFor(String ga) {
        return cache.computeIfAbsent(ga, key -> rules.reversed().stream().filter(rule -> rule.matches(key))
                .map(Rule::constraint).findFirst()).orElse(null);
    }

    /**
     * Renovate never updates these packages, so they cannot bypass a pin.
     */
    public boolean isDisabled(String ga) {
        return disabled.stream().anyMatch(rule -> rule.matches(ga));
    }

    /**
     * GA to exact pinned version, as Renovate applies it.
     */
    public Map<String, String> exactPins() {
        Map<String, String> result = new TreeMap<>();
        rules.stream().flatMap(rule -> rule.exact().stream()).forEach(ga -> {
            Constraint c = constraintFor(ga);
            if (c != null && c.exact() != null) {
                result.put(ga, c.exact());
            }
        });
        return result;
    }

    public int size() {
        return rules.size();
    }

    public List<String> skipped() {
        return skipped;
    }

    // Renovate package name and version patterns: /regex/flags, minimatch globs and exact names

    /**
     * {@code /body/flags} as a Java pattern, or null if the value is not a regex literal.
     *
     * @throws PatternSyntaxException also for flags other than {@code i}: Java has no exact counterpart
     */
    private static Pattern regex(String value) {
        Matcher m = JS_REGEX.matcher(value);
        if (!m.matches()) {
            return null;
        }
        if (!m.group(2).replace("i", "").isEmpty()) {
            throw new PatternSyntaxException("unsupported flags " + m.group(2), value, -1);
        }
        return Pattern.compile(m.group(1), m.group(2).isEmpty() ? 0 : Pattern.CASE_INSENSITIVE);
    }

    /**
     * A minimatch-style glob as a Java pattern, or null if the value has no wildcards.
     */
    private static Pattern glob(String value) {
        if (value.indexOf('*') < 0 && value.indexOf('?') < 0) {
            return null;
        }
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*' && i + 1 < value.length() && value.charAt(i + 1) == '*') {
                rx.append(".*");
                i++;
            } else if (c == '*') {
                rx.append("[^/]*");
            } else if (c == '?') {
                rx.append("[^/]");
            } else {
                rx.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(rx.toString());
    }

    private static boolean isExactName(String value) {
        return !value.startsWith("!") && regex(value) == null && glob(value) == null;
    }

    /**
     * A package name pattern compiled once: regexes match anywhere, globs the whole value, other values exactly.
     */
    private static Predicate<String> matcher(String pattern) {
        Pattern rx = regex(pattern);
        if (rx != null) {
            return value -> rx.matcher(value).find();
        }
        Pattern glob = glob(pattern);
        return glob != null ? value -> glob.matcher(value).matches() : pattern::equals;
    }
}
