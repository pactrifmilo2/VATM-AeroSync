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

    private static final String FIND_ROUTES_SQL = """
            SELECT DISTINCT TRIM(VIA) AS VIA, UPPER(TRIM(OPER)) AS OPER
              FROM M_VIA
             WHERE UPPER(TRIM(FROM_AIRP)) = ?
               AND UPPER(TRIM(TO_AIRP)) = ?
               AND VIA IS NOT NULL
            """;

    public String resolve(Connection connection,
                          String fromAirport,
                          String toAirport,
                          String operatorId,
                          String documentVia) throws SQLException {
        String from = normalize(fromAirport);
        String to = normalize(toAirport);
        String operator = normalize(operatorId);
        String existing = normalize(documentVia);
        Set<String> operatorRoutes = new LinkedHashSet<>();
        Set<String> genericRoutes = new LinkedHashSet<>();
        Set<String> allRoutes = new LinkedHashSet<>();

        try (PreparedStatement statement = connection.prepareStatement(FIND_ROUTES_SQL)) {
            statement.setString(1, from);
            statement.setString(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String route = normalize(rows.getString("VIA"));
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
            return selectUnique(from, to, operator, existing, operatorRoutes);
        }
        if (!genericRoutes.isEmpty()) {
            return selectUnique(from, to, operator, existing, genericRoutes);
        }
        if (!allRoutes.isEmpty()) {
            return selectUnique(from, to, operator, existing, allRoutes);
        }
        if (!existing.isBlank()) {
            return existing;
        }
        throw new AtfmReferenceDataException(
                "ATFM route not found: M_VIA.FROM_AIRP=" + from
                        + ", TO_AIRP=" + to
                        + ", OPER=" + operator);
    }

    private String selectUnique(String from,
                                String to,
                                String operator,
                                String existing,
                                Set<String> routes) {
        if (routes.size() == 1) {
            return routes.iterator().next();
        }
        if (!existing.isBlank() && routes.contains(existing)) {
            return existing;
        }
        throw new AtfmReferenceDataException(
                "Ambiguous ATFM routes in M_VIA for " + from + " -> " + to
                        + ", OPER=" + operator + ": " + String.join(", ", routes));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
