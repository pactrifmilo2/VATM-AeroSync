package vatm.aerosync.worker.atfm;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AtfmPermitIdNormalizer {

    private static final Pattern NORMALIZED = Pattern.compile(
            "(?iu)(?<type>LD|O\\s*/\\s*F)\\s+(?<number>\\d{4}[A-Z]|\\d{5})"
                    + "\\s*/\\s*(?<season>[SW])\\s*/\\s*CHK\\s*/\\s*(?<year>20\\d{2})\\b");
    private static final Pattern SEASONAL_REFERENCE = Pattern.compile(
            "(?iu)(?<type>LD|O\\s*/?\\s*F)\\s*-?\\s*(?<number>\\d{1,5})"
                    + "\\s*/\\s*(?<version>[A-Z])\\s*/\\s*(?<season>[SW])"
                    + "\\s*/\\s*(?<year>20\\d{2})(?:\\s*VN)?\\b");
    private static final Pattern DATED_REFERENCE = Pattern.compile(
            "(?iu)(?<type>LD|O\\s*/?\\s*F)\\s*-?\\s*(?<number>\\d{1,5})"
                    + "\\s*/\\s*\\d{1,2}\\s*/\\s*(?<year>20\\d{2})(?:\\s*VN)?\\b");

    private AtfmPermitIdNormalizer() {
    }

    public static Optional<String> normalizeReference(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        Matcher normalized = NORMALIZED.matcher(value);
        if (normalized.find()) {
            return Optional.of("%s %s/%s/CHK/%s".formatted(
                    normalizedType(normalized.group("type")),
                    normalized.group("number").toUpperCase(Locale.ROOT),
                    normalized.group("season").toUpperCase(Locale.ROOT),
                    normalized.group("year")));
        }

        Matcher seasonal = SEASONAL_REFERENCE.matcher(value);
        if (seasonal.find()) {
            String number = "%04d".formatted(Integer.parseInt(seasonal.group("number")))
                    + seasonal.group("version").toUpperCase(Locale.ROOT);
            return Optional.of("%s %s/%s/CHK/%s".formatted(
                    normalizedType(seasonal.group("type")), number,
                    seasonal.group("season").toUpperCase(Locale.ROOT),
                    seasonal.group("year")));
        }

        Matcher dated = DATED_REFERENCE.matcher(value);
        if (dated.find()) {
            return Optional.of("%s %05d/S/CHK/%s".formatted(
                    normalizedType(dated.group("type")),
                    Integer.parseInt(dated.group("number")),
                    dated.group("year")));
        }
        return Optional.empty();
    }

    private static String normalizedType(String value) {
        return value.replaceAll("[^A-Za-z]", "").equalsIgnoreCase("LD") ? "LD" : "O/F";
    }
}
