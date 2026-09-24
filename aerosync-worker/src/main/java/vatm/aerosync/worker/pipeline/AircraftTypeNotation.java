package vatm.aerosync.worker.pipeline;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

final class AircraftTypeNotation {

    private AircraftTypeNotation() {
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalizedSeparators = value.toUpperCase(Locale.ROOT).trim()
                .replaceAll("\\s+(?:-|\\u2013|\\u2014)\\s+", "/")
                .replaceAll("(?<=\\b[A-Z0-9]{3})-(?=[A-Z0-9]{3}\\b)", "/");
        return Arrays.stream(normalizedSeparators.split(
                        "\\s*(?:/|;|,|\\bOR\\b)\\s*"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .map(AircraftTypeNotation::prefixFamily)
                .collect(Collectors.joining("/"));
    }

    private static String prefixFamily(String value) {
        if (value.matches("^3[A-Z0-9].*")) {
            return "A" + value;
        }
        if (value.matches("^7[A-Z0-9].*")) {
            return "B" + value;
        }
        return value;
    }
}
