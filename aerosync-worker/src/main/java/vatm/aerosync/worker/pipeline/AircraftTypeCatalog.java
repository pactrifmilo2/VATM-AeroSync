package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Component
class AircraftTypeCatalog {

    private static final String RESOURCE = "permit-reference/aircraft-types.yaml";

    private final List<Entry> entries;

    AircraftTypeCatalog() {
        this.entries = load();
    }

    DocxPermitFormatProfile.AircraftMapping resolve(String value) {
        String key = canonical(value);
        Entry exact = entries.stream()
                .filter(entry -> entry.aliases().stream()
                        .map(this::canonical)
                        .anyMatch(key::equals))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact.toMapping();
        }
        for (String token : value == null ? new String[0] : value.split("(?iu)\\s*(?:/|;|,|\\bOR\\b)\\s*")) {
            String tokenKey = canonical(token);
            Entry match = entries.stream()
                    .filter(entry -> entry.aliases().stream()
                            .map(this::canonical)
                            .anyMatch(tokenKey::equals))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                return match.toMapping();
            }
        }
        return null;
    }

    private List<Entry> load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            Catalog catalog = mapper.readValue(input, Catalog.class);
            if (catalog.entries() == null || catalog.entries().isEmpty()) {
                throw new IllegalStateException("Aircraft type catalog is empty");
            }
            return List.copyOf(catalog.entries());
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

    private record Entry(List<String> aliases, long craftId, BigDecimal mtow) {
        private DocxPermitFormatProfile.AircraftMapping toMapping() {
            return new DocxPermitFormatProfile.AircraftMapping(aliases, craftId, mtow);
        }
    }
}
