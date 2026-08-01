package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class PermitOperatorCatalog {

    private static final String RESOURCE = "permit-reference/permit-oper.yaml";
    private static final Pattern IATA_FLIGHT_NUMBER = Pattern.compile(
            "^(?<iata>[A-Z0-9]{2})(?<suffix>\\d[A-Z0-9]*)$");
    private static final Pattern ICAO_FLIGHT_NUMBER = Pattern.compile(
            "^(?<icao>[A-Z]{3})\\d[A-Z0-9]*$");

    private final Map<String, List<String>> icaoCodesByIata;

    PermitOperatorCatalog() {
        this.icaoCodesByIata = load();
    }

    String normalizeFlightNumber(String value, String operatorId) {
        String compact = canonical(value);
        Matcher matcher = IATA_FLIGHT_NUMBER.matcher(compact);
        if (!matcher.matches()) {
            return compact;
        }

        List<String> candidates = icaoCodesByIata.get(matcher.group("iata"));
        if (candidates == null || candidates.isEmpty()) {
            return compact;
        }

        String normalizedOperator = canonical(operatorId);
        String icaoCode = candidates.stream()
                .filter(candidate -> candidate.equals(normalizedOperator))
                .findFirst()
                .orElse(candidates.size() == 1 ? candidates.getFirst() : null);
        if (icaoCode == null) {
            return compact;
        }
        return icaoCode + matcher.group("suffix");
    }

    String inferOperator(String flightNumber) {
        String compact = canonical(flightNumber);
        Matcher icaoMatcher = ICAO_FLIGHT_NUMBER.matcher(compact);
        if (icaoMatcher.matches()) {
            return icaoMatcher.group("icao");
        }

        Matcher iataMatcher = IATA_FLIGHT_NUMBER.matcher(compact);
        if (!iataMatcher.matches()) {
            return null;
        }
        List<String> candidates = icaoCodesByIata.get(iataMatcher.group("iata"));
        return candidates != null && candidates.size() == 1
                ? candidates.getFirst()
                : null;
    }

    private Map<String, List<String>> load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = mapper.readTree(input);
            if (root == null || !root.isObject() || root.isEmpty()) {
                throw new IllegalStateException("Permit operator catalog is empty");
            }

            Map<String, List<String>> loaded = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                String iataCode = canonical(entry.getKey());
                if (!iataCode.matches("[A-Z0-9]{2}")) {
                    throw new IllegalStateException(
                            "Invalid IATA operator code in permit catalog: " + entry.getKey());
                }
                List<String> icaoCodes = readIcaoCodes(entry.getValue(), iataCode);
                List<String> previous = loaded.putIfAbsent(iataCode, icaoCodes);
                if (previous != null && !previous.equals(icaoCodes)) {
                    throw new IllegalStateException(
                            "Conflicting permit operator mapping for " + iataCode);
                }
            });
            return Map.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load permit operator catalog " + RESOURCE, exception);
        }
    }

    private List<String> readIcaoCodes(JsonNode value, String iataCode) {
        List<String> source = new ArrayList<>();
        if (value.isTextual()) {
            source.add(value.asText());
        } else if (value.isArray()) {
            value.forEach(candidate -> {
                if (!candidate.isTextual()) {
                    throw new IllegalStateException(
                            "Invalid ICAO operator mapping for " + iataCode);
                }
                source.add(candidate.asText());
            });
        } else {
            throw new IllegalStateException(
                    "Invalid ICAO operator mapping for " + iataCode);
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String candidate : source) {
            String icaoCode = canonical(candidate);
            if (!icaoCode.matches("[A-Z0-9]{3}")) {
                throw new IllegalStateException(
                        "Invalid ICAO operator code for " + iataCode + ": " + candidate);
            }
            normalized.add(icaoCode);
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException(
                    "Missing ICAO operator code for " + iataCode);
        }
        return List.copyOf(normalized);
    }

    private String canonical(String value) {
        return value == null
                ? ""
                : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
