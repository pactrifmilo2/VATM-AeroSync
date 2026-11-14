package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class AircraftTypeCatalog {

    private static final String RESOURCE = "permit-reference/aircraft-types.yaml";
    private static final Pattern VARIANT_SUFFIX = Pattern.compile(
            "(?i)^([A-Z]{1,2}\\d{3,4})-\\d{3,4}[A-Z]?");

    private final Map<String, String> aliases;

    AircraftTypeCatalog() {
        this.aliases = load();
    }

    List<String> candidates(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        String exact = aliases.get(canonical(value));
        if (exact != null) {
            return List.of(exact);
        }

        Set<String> candidates = new LinkedHashSet<>();
        for (String token : AircraftTypeNotation.normalize(value)
                .split("\\s*(?:/|;|,|\\bOR\\b)\\s*")) {
            String clean = token.trim();
            if (!clean.isBlank()) {
                addCandidate(candidates, clean);
            }
        }
        return List.copyOf(candidates);
    }

    List<String> knownCandidates(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String exact = aliases.get(canonical(value));
        if (exact != null) {
            return List.of(exact);
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (String token : AircraftTypeNotation.normalize(value)
                .split("\\s*(?:/|;|,|\\bOR\\b)\\s*")) {
            String clean = token.trim();
            addKnownCandidate(candidates, clean);
        }
        return List.copyOf(candidates);
    }

    private void addCandidate(Set<String> candidates, String source) {
        String exact = aliases.get(canonical(source));
        if (exact != null) {
            candidates.add(exact);
            return;
        }
        String base = baseAircraftType(source);
        String lookup = base == null ? source : base;
        candidates.add(aliases.getOrDefault(canonical(lookup), lookup));
    }

    private void addKnownCandidate(Set<String> candidates, String source) {
        String exact = aliases.get(canonical(source));
        if (exact != null) {
            candidates.add(exact);
            return;
        }
        String base = baseAircraftType(source);
        String known = base == null ? null : aliases.get(canonical(base));
        if (known != null) {
            candidates.add(known);
        }
    }

    private String baseAircraftType(String value) {
        Matcher matcher = VARIANT_SUFFIX.matcher(value.trim());
        return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private Map<String, String> load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            Catalog catalog = mapper.readValue(input, Catalog.class);
            if (catalog.entries() == null || catalog.entries().isEmpty()) {
                throw new IllegalStateException("Aircraft type catalog is empty");
            }
            Map<String, String> loaded = new LinkedHashMap<>();
            for (Entry entry : catalog.entries()) {
                if (entry.aliases() == null || entry.aliases().isEmpty()
                        || entry.type() == null || entry.type().isBlank()) {
                    throw new IllegalStateException(
                            "Every aircraft alias entry requires aliases and a target type");
                }
                for (String alias : entry.aliases()) {
                    String previous = loaded.putIfAbsent(canonical(alias), entry.type().trim());
                    if (previous != null && !canonical(previous).equals(canonical(entry.type()))) {
                        throw new IllegalStateException(
                                "Conflicting aircraft alias mapping for " + alias);
                    }
                }
            }
            return Map.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load aircraft type catalog " + RESOURCE, exception);
        }
    }

    private String canonical(String value) {
        String folded = Normalizer.normalize(
                        value == null ? "" : value,
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private record Catalog(List<Entry> entries) {
    }

    private record Entry(List<String> aliases, String type) {
    }
}
