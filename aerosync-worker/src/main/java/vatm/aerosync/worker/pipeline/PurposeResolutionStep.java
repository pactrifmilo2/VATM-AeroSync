package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.worker.atfm.AtfmReferenceDataException;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves purpose text against the ATFM purpose dictionary before reconciliation. */
@Component
public class PurposeResolutionStep {

    private static final String LOAD_PURPOSES_SQL = """
            SELECT PURPOSE_CODE, PURPOSE_NAME
              FROM M_FLY_PURPOSE
             WHERE PURPOSE_CODE IS NOT NULL
               AND PURPOSE_NAME IS NOT NULL
            ORDER BY PURPOSE_CODE
            """;

    private final AtfmDatabaseProperties properties;

    public PurposeResolutionStep(AtfmDatabaseProperties properties) {
        this.properties = properties;
    }

    public void resolve(ProcessingContext context) {
        SchedulePermit permit = context.getSchedulePermit();
        if (permit == null || permit.flights().isEmpty()) {
            return;
        }
        if (properties.getUrl().isBlank() || properties.getUsername().isBlank()) {
            throw new AtfmReferenceDataException(
                    "ATFM purpose dictionary requires a database URL and username");
        }

        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword())) {
            List<PurposeEntry> entries = load(connection);
            List<ScheduleFlight> resolved = new ArrayList<>(permit.flights().size());
            for (ScheduleFlight flight : permit.flights()) {
                String content = context.isNoReplacementMapped()
                        ? newPurposeContent(permit.rawContent()) : permit.rawContent();
                String purpose = resolvePurpose(entries, content, flight);
                resolved.add(flight.withPurposeId(purpose));
            }
            context.setSchedulePermit(permit.withFlights(resolved));
        } catch (SQLException exception) {
            throw new AtfmReferenceDataException(
                    "Failed to load purpose dictionary from M_FLY_PURPOSE: " + exception.getMessage());
        }
    }

    private List<PurposeEntry> load(Connection connection) throws SQLException {
        List<PurposeEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LOAD_PURPOSES_SQL);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String code = clean(rows.getString("PURPOSE_CODE"));
                String name = clean(rows.getString("PURPOSE_NAME"));
                if (code != null && name != null) {
                    entries.add(new PurposeEntry(code, name));
                }
            }
        }
        return List.copyOf(entries);
    }

    static String resolvePurpose(List<PurposeEntry> entries,
                                 String rawContent,
                                 ScheduleFlight flight) {
        String fallback = flight.purposeId();
        String flightText = flightPurposeText(rawContent, flight.flightNumber());
        Match flightMatch = bestMatch(entries, flightText);
        if (flightMatch != null) return flightMatch.code();
        String routeText = routeDescription(rawContent, flight.fromAirport(), flight.toAirport());
        Match routeMatch = bestMatch(entries, routeText);
        if (routeMatch != null) return routeMatch.code();
        // Do not search the entire document: airline names (for example
        // "... CARGO") and administrative notes ("Other details unchanged")
        // are not the flight purpose. Only an explicitly labelled purpose
        // field may override the purpose selected by the YAML profile.
        Match labelledMatch = bestMatch(entries, labelledPurposeText(rawContent));
        if (labelledMatch != null) return labelledMatch.code();
        return fallback;
    }

    private static String newPurposeContent(String raw) {
        String content = RevisionDocumentText.newValues(raw);
        AirportCodeCatalog airports = new AirportCodeCatalog();
        Matcher routes = Pattern.compile("(?imu)^\\s*([A-Z]{3,4})\\s*[-–—]\\s*([A-Z]{3,4})\\s*:").matcher(content);
        StringBuilder normalized = new StringBuilder();
        while (routes.find()) routes.appendReplacement(normalized, Matcher.quoteReplacement(
                airports.normalize(routes.group(1)) + "-" + airports.normalize(routes.group(2)) + ":"));
        routes.appendTail(normalized);
        return normalized.toString();
    }

    static boolean hasExplicitNewPurpose(String raw, ScheduleFlight flight) {
        String content = newPurposeContent(raw);
        String labelled = Objects.toString(flightPurposeText(content, flight.flightNumber()), "")
                + " " + labelledPurposeText(content);
        String meaningful = canonical(labelled);
        if (!meaningful.isBlank() && !meaningful.matches(".*(?:UNCHANGED|NO CHANGE|KHONG THAY DOI).*$")) return true;
        // A sector-labelled airway is not a purpose change. Only recognized purpose
        // descriptions may override inherited PURPOSE when attached to a sector.
        String route = canonical(routeDescription(content, flight.fromAirport(), flight.toAirport()));
        return Pattern.compile("\\b(?:PAX|PASSENGER|CARGO|FREIGHTER|FER|FERRY|TECH|TECHNICAL|AMB|AMBULANCE|VIP|PRIVATE|BUSINESS|CHARTER|HANH KHACH|HANG HOA)\\b")
                .matcher(route).find();
    }

    private static Match bestMatch(List<PurposeEntry> entries, String text) {
        String canonicalText = canonical(text);
        if (canonicalText.isBlank()) {
            return null;
        }
        return entries.stream()
                .flatMap(entry -> entry.synonyms().stream()
                        .map(synonym -> new Match(entry.code(), synonym, score(canonicalText, synonym))))
                .filter(match -> match.score() > 0)
                .max(Comparator.comparingInt(Match::score)
                        .thenComparing(Match::synonym)
                        .thenComparing(Match::code))
                .orElse(null);
    }

    private static int score(String text, String synonym) {
        String value = canonical(synonym);
        if (value.isBlank()) {
            return 0;
        }
        if (value.length() <= 2) {
            return containsWord(text, value) ? value.length() * 10 : 0;
        }
        return containsPhrase(text, value) ? value.length() * 10 : 0;
    }

    private static boolean containsPhrase(String text, String phrase) {
        return (" " + text + " ").contains(" " + phrase + " ")
                || text.equals(phrase)
                || text.startsWith(phrase + " ")
                || text.endsWith(" " + phrase);
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(word) + "(?![A-Z0-9])")
                .matcher(text)
                .find();
    }

    private static String routeDescription(String rawContent, String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile(
                        "(?imu)^\\s*" + Pattern.quote(from) + "\\s*[-\\u2013\\u2014]\\s*"
                                + Pattern.quote(to) + "\\s*:\\s*(?<value>[^\\n]+)")
                .matcher(Objects.requireNonNullElse(rawContent, ""));
        return matcher.find() ? matcher.group("value") : null;
    }

    private static String labelledPurposeText(String rawContent) {
        String value = Objects.requireNonNullElse(rawContent, "");
        Pattern label = Pattern.compile(
                "(?imu)^[ \\t]*(?:\\d+(?:\\.\\d+)*\\.?\\s*)?(?:purpose(?:\\s+of\\s+flight(?:\\(s\\)|s)?)?|flight\\s+purpose|"
                        + "mục\\s*đích(?:\\s+chuyến\\s+bay)?)\\s*[:\\-]\\s*(?<value>[^\\r\\n]+)");
        StringBuilder result = new StringBuilder();
        Matcher matcher = label.matcher(value);
        while (matcher.find()) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(matcher.group("value"));
        }
        return result.toString();
    }

    private static String flightPurposeText(String rawContent, String flightNumber) {
        if (flightNumber == null || flightNumber.isBlank()) return null;
        Matcher matcher = Pattern.compile("(?imu)^\\s*" + Pattern.quote(flightNumber)
                + "\\s*(?:[|\\t:;-]\\s*|\\s+)(?:PURPOSE|MỤC\\s*ĐÍCH)"
                + "(?:\\s+(?:OF\\s+FLIGHT|CHUYẾN\\s+BAY))?\\s*[:\\t| -]+(?<value>[^\\r\\n]+)")
                .matcher(Objects.requireNonNullElse(rawContent, ""));
        return matcher.find() ? matcher.group("value") : null;
    }

    private static String canonical(String value) {
        String folded = Normalizer.normalize(Objects.requireNonNullElse(value, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
        return folded.replaceAll("[^A-Z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record PurposeEntry(String code, String name) {
        List<String> synonyms() {
            List<String> values = new ArrayList<>();
        for (String synonym : name.split(";")) {
                String value = canonical(synonym);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            String normalizedCode = canonical(code);
            if (!normalizedCode.isBlank()) {
                values.add(normalizedCode);
            }
            return values.stream().distinct().toList();
        }
    }

    private record Match(String code, String synonym, int score) {
    }
}
