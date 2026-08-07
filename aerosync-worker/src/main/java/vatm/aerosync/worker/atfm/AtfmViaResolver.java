package vatm.aerosync.worker.atfm;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
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

    private static final String FIND_ROUTES_SQL = """
            SELECT DISTINCT UPPER(TRIM(VIA)) AS VIA, UPPER(TRIM(OPER)) AS OPER
              FROM M_VIA
             WHERE UPPER(TRIM(FROM_AIRP)) = ?
               AND UPPER(TRIM(TO_AIRP)) = ?
               AND VIA IS NOT NULL
             ORDER BY 1, 2
            """;

    public String resolve(Connection connection,
                          String fromAirport,
                          String toAirport,
                          String operatorId,
                          String documentVia) throws SQLException {
        String from = normalize(fromAirport);
        String to = normalize(toAirport);
        String operator = normalize(operatorId);
        String existing = normalizeRoute(documentVia);
        if (!existing.isBlank()) {
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

        // Keep M_VIA as a compatibility fallback for airport pairs that have
        // not yet been migrated into M_AIRPORT_ROUTE.
        Set<String> operatorRoutes = new LinkedHashSet<>();
        Set<String> genericRoutes = new LinkedHashSet<>();
        Set<String> allRoutes = new LinkedHashSet<>();

        try (PreparedStatement statement = connection.prepareStatement(FIND_ROUTES_SQL)) {
            statement.setString(1, from);
            statement.setString(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String route = normalizeRoute(rows.getString("VIA"));
                    if (route.isBlank()) {
                        continue;
                    }
                    String routeOperator = normalize(rows.getString("OPER"));
                    allRoutes.add(route);
                    if (routeOperator.equals(operator)) {
                        operatorRoutes.add(route);
                    } else if (routeOperator.isBlank()) {
                        genericRoutes.add(route);
                    }
                }
            }
        }

        if (!operatorRoutes.isEmpty()) {
            return selectFirst(operatorRoutes);
        }
        if (!genericRoutes.isEmpty()) {
            return selectFirst(genericRoutes);
        }
        if (!allRoutes.isEmpty()) {
            return selectFirst(allRoutes);
        }
        throw new AtfmReferenceDataException(
                "ATFM route not found: M_AIRPORT_ROUTE.FROM_AIRP=" + from
                        + ", TO_AIRP=" + to
                        + " (also checked M_VIA for OPER=" + operator + ")");
    }

    private String selectFirst(Set<String> routes) {
        return routes.iterator().next();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRoute(String value) {
        return normalize(value)
                .replaceAll("\\s*/\\s*", "/")
                .replaceAll("/+$", "");
    }
}
