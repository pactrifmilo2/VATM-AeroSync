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

@Component
class AircraftTypeCatalog {

    private static final String RESOURCE = "permit-reference/aircraft-types.yaml";

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
        for (String token : value.split("(?iu)\\s*(?:/|;|,|\\bOR\\b)\\s*")) {
            String clean = token.trim();
            if (!clean.isBlank()) {
                candidates.add(aliases.getOrDefault(canonical(clean), clean));
            }
        }
        return List.copyOf(candidates);
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
