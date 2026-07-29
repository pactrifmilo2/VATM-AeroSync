package vatm.aerosync.worker.atfm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
class AircraftTypePreferenceCatalog {

    private static final String RESOURCE = "permit-reference/aircraft-preferences.yaml";

    private final Map<String, Long> preferredCraftIds;

    AircraftTypePreferenceCatalog() {
        this.preferredCraftIds = load();
    }

    Long preferredCraftId(String code) {
        return preferredCraftIds.get(canonical(code));
    }

    private Map<String, Long> load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            Catalog catalog = mapper.readValue(input, Catalog.class);
            if (catalog.entries() == null) {
                return Map.of();
            }
            Map<String, Long> loaded = new LinkedHashMap<>();
            for (Entry entry : catalog.entries()) {
                if (entry.code() == null || entry.code().isBlank() || entry.craftId() <= 0) {
                    throw new IllegalStateException(
                            "Every aircraft preference requires a code and positive craftId");
                }
                Long previous = loaded.putIfAbsent(canonical(entry.code()), entry.craftId());
                if (previous != null && previous.longValue() != entry.craftId()) {
                    throw new IllegalStateException(
                            "Conflicting aircraft preference for " + entry.code());
                }
            }
            return Map.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load aircraft preferences " + RESOURCE, exception);
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

    private record Entry(String code, long craftId) {
    }
}
