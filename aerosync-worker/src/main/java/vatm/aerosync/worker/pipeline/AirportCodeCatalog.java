package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
class AirportCodeCatalog {

    private static final String RESOURCE = "permit-reference/airport-codes.yaml";

    private final Map<String, String> iataToIcao;
    private final Set<String> knownCodes;

    AirportCodeCatalog() {
        this.iataToIcao = load();
        this.knownCodes = java.util.stream.Stream.concat(
                        iataToIcao.keySet().stream(), iataToIcao.values().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    String normalize(String value) {
        String code = canonicalize(value);
        return iataToIcao.getOrDefault(code, code);
    }

    String canonicalize(String value) {
        return value == null
                ? ""
                : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    boolean isKnown(String value) {
        return knownCodes.contains(canonicalize(value));
    }

    private Map<String, String> load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, String> configured =
                    mapper.readValue(input, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> normalized = new LinkedHashMap<>();
            configured.forEach((iata, icao) -> normalized.put(
                    iata.toUpperCase(Locale.ROOT),
                    icao.toUpperCase(Locale.ROOT)));
            return Map.copyOf(normalized);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load airport code catalog " + RESOURCE, exception);
        }
    }
}
