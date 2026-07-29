package vatm.aerosync.worker.atfm;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

@Component
public class AtfmAirportCodeResolver {

    private static final String FIND_BY_IATA_SQL = """
            SELECT DISTINCT TRIM(AE_CODE) AS AE_CODE
              FROM M_AERO
             WHERE UPPER(TRIM(AE_IATA)) = ?
               AND AE_CODE IS NOT NULL
            """;

    public String resolve(Connection connection, String sourceCode) throws SQLException {
        String normalized = normalize(sourceCode);
        if (normalized.length() == 4) {
            return normalized;
        }
        if (normalized.length() != 3) {
            throw new AtfmReferenceDataException("Invalid airport code: " + sourceCode);
        }

        String resolved = null;
        try (PreparedStatement statement = connection.prepareStatement(FIND_BY_IATA_SQL)) {
            statement.setString(1, normalized);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String candidate = normalize(rows.getString("AE_CODE"));
                    if (candidate.length() != 4) {
                        throw new AtfmReferenceDataException(
                                "Invalid M_AERO.AE_CODE mapping for AE_IATA=" + normalized + ": " + candidate);
                    }
                    if (resolved != null && !resolved.equals(candidate)) {
                        throw new AtfmReferenceDataException(
                                "Ambiguous M_AERO airport mapping for AE_IATA=" + normalized);
                    }
                    resolved = candidate;
                }
            }
        }
        if (resolved == null) {
            throw new AtfmReferenceDataException(
                    "ATFM airport mapping not found: M_AERO.AE_IATA=" + normalized);
        }
        return resolved;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
