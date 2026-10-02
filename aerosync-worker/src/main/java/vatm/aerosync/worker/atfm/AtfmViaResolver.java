package vatm.aerosync.worker.atfm;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

@Component
public class AtfmViaResolver {

    private static final String FIND_AIRPORT_ROUTE_SQL = """
            SELECT ROUTE
              FROM M_AIRPORT_ROUTE
             WHERE UPPER(TRIM(FROM_AIRP)) = ?
               AND UPPER(TRIM(TO_AIRP)) = ?
               AND ROUTE IS NOT NULL
               AND TRIM(ROUTE) IS NOT NULL
             ORDER BY NVL(SUMMARY, 0) DESC, ID DESC
            """;
    private static final Set<String> NON_ROUTE_WORDS = Set.of(
            "AS", "PER", "SEE", "NOTE", "NOTES", "REMARK", "REMARKS",
            "NIL", "NONE", "ROUTE", "SUBJECT", "APPROVAL", "ATTACHED",
            "ABOVE", "BELOW", "SAME");

    public static final String DEFAULT_VIA_PREFIX =
            "Other permitted airways published in Viet Nams AIP/";

    public String resolve(Connection connection,
                          String fromAirport,
                          String toAirport,
                          String operatorId,
                          String documentVia) throws SQLException {
        String from = normalize(fromAirport);
        String to = normalize(toAirport);
        String rawDocumentRoute = normalize(documentVia);
        String existing = normalizeRoute(documentVia);
        if (!rawDocumentRoute.isBlank()) {
            if (existing.isBlank() || !isReadableRoute(existing)) {
                throw new AtfmDocumentRouteException(
                        "BR-ROUTE-DOCUMENT-UNREADABLE: Cannot read route from permit file");
            }
            return existing;
        }

        // M_AIRPORT_ROUTE is the authoritative airport-pair route catalogue.
        // Use the most recently maintained/highest-summary route when the Word
        // document does not publish an airway table.
        try (PreparedStatement statement = connection.prepareStatement(FIND_AIRPORT_ROUTE_SQL)) {
            statement.setString(1, from);
            statement.setString(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String route = normalizeRoute(rows.getString("ROUTE"));
                    if (!route.isBlank()) {
                        return route;
                    }
                }
            }
        }

        throw new AtfmReferenceDataException(
                "BR-ROUTE-NOT-FOUND: ATFM route not found: " + from + " -> " + to);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRoute(String value) {
        String normalized = normalize(value);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("atfm route not found")
                || lower.contains("đường bay không có trong từ điển atfm")) {
            return "";
        }
        String route = normalized
                .replaceAll("(?iu)\\s+(?:-|\\u2013|\\u2014)\\s+", "/")
                .replaceAll("(?iu)\\s+HOẶC\\s+", "/")
                .replaceAll("(?iu)(?<=[A-Z0-9])-(?=[A-Z][A-Z0-9]{1,5}(?:$|[-/\\s]))", "/")
                .replaceAll("(?iu)\\s*(?:,|;|\\bOR\\b)\\s*", "/")
                .replaceAll("\\s*/\\s*", "/")
                .replaceAll("(?<=\\b[A-Z0-9]{2,15})\\s+(?=[A-Z0-9]{2,15}\\b)", "/")
                .replaceAll("/{2,}", "/")
                .replaceAll("/+$", "");
        return java.util.Arrays.stream(route.split("/"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .filter(token -> !token.matches("[A-Z]+"))
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
    }

    private boolean isReadableRoute(String value) {
        String[] tokens = value.split("[\\s/]+");
        boolean hasRouteToken = false;
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (!token.matches("[A-Z0-9]{1,15}") || NON_ROUTE_WORDS.contains(token)) {
                return false;
            }
            hasRouteToken |= token.equals("DCT")
                    || token.matches(".*\\d.*")
                    || token.matches("[A-Z]{3,5}");
        }
        return hasRouteToken;
    }
}
