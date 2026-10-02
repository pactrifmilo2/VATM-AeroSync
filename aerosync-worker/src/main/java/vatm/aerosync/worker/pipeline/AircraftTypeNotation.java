package vatm.aerosync.worker.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AircraftTypeNotation {

    private static final Pattern COMPACT_AIRCRAFT = Pattern.compile(
            "(?iu)(?:[A-Z]{1,2}\\d{3,4}(?:-\\d{3,4}[A-Z]?)?|[A-Z]\\d{2}[A-Z]|\\d{3}[A-Z]?)");

    private AircraftTypeNotation() {
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalized = value.toUpperCase(Locale.ROOT).trim()
                .replaceAll("\\s+(?:-|\\u2013|\\u2014)\\s+", "/")
                .replaceAll("(?<=\\b\\d{3})-(?=\\d{3}\\b)", "/");
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (String part : normalized.split("\\s*(?:/|;|,|\\bOR\\b)\\s*")) {
            String token = part.trim();
            if (token.isBlank()) {
                continue;
            }
            Matcher matcher = COMPACT_AIRCRAFT.matcher(token);
            List<String> extracted = new ArrayList<>();
            while (matcher.find()) {
                extracted.add(matcher.group().toUpperCase(Locale.ROOT));
            }
            if (extracted.isEmpty()) {
                types.add(token);
            } else {
                types.addAll(extracted);
            }
        }
        return String.join("/", types);
    }
}
