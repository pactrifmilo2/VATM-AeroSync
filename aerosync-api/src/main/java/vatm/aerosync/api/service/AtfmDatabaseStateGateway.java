package vatm.aerosync.api.service;

import org.springframework.stereotype.Component;
import vatm.aerosync.api.config.TestReplayProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

@Component
public class AtfmDatabaseStateGateway {

    private static final String[] MASTER_TABLES = {"T_PERMMASTER_SC", "T_PERMMASTER_NO"};
    private final TestReplayProperties properties;

    public AtfmDatabaseStateGateway(TestReplayProperties properties) {
        this.properties = properties;
    }

    public DatabaseState state(Long masterId, Long permId, String normalizedPermitId,
                               boolean terminal, boolean processingError) {
        if (!terminal) {
            return DatabaseState.PENDING;
        }
        if (processingError) {
            return DatabaseState.ERROR;
        }
        if (normalizedPermitId == null || normalizedPermitId.isBlank()) {
            return DatabaseState.UNKNOWN;
        }
        try (Connection connection = openConnection()) {
            for (String table : MASTER_TABLES) {
                if (exists(connection, table, masterId, permId, normalizedPermitId)) {
                    return DatabaseState.FOUND;
                }
            }
            return DatabaseState.MISSING;
        } catch (SQLException | RuntimeException exception) {
            return DatabaseState.UNKNOWN;
        }
    }

    public java.util.Map<Long, DatabaseState> states(java.util.List<Candidate> candidates) {
        java.util.Map<Long, DatabaseState> result = new java.util.LinkedHashMap<>();
        java.util.List<Candidate> lookups = new java.util.ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!candidate.terminal()) result.put(candidate.key(), DatabaseState.PENDING);
            else if (candidate.processingError()) result.put(candidate.key(), DatabaseState.ERROR);
            else if (candidate.normalizedPermitId() == null || candidate.normalizedPermitId().isBlank())
                result.put(candidate.key(), DatabaseState.UNKNOWN);
            else lookups.add(candidate);
        }
        if (lookups.isEmpty()) return result;
        try (Connection connection = openConnection()) {
            for (Candidate candidate : lookups) {
                boolean found = false;
                for (String table : MASTER_TABLES) {
                    if (exists(connection, table, candidate.masterId(), candidate.permId(),
                            candidate.normalizedPermitId())) {
                        found = true; break;
                    }
                }
                result.put(candidate.key(), found ? DatabaseState.FOUND : DatabaseState.MISSING);
            }
        } catch (SQLException | RuntimeException exception) {
            lookups.forEach(candidate -> result.put(candidate.key(), DatabaseState.UNKNOWN));
        }
        return result;
    }

    public record Candidate(Long key, Long masterId, Long permId, String normalizedPermitId,
                            boolean terminal, boolean processingError) { }

    private boolean exists(Connection connection, String table, Long masterId, Long permId,
                           String normalizedPermitId) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT 1 FROM " + table + " WHERE UPPER(TRIM(PERMNBR_ID)) = ?");
        if (masterId != null) sql.append(" AND ID = ?");
        if (permId != null) sql.append(" AND PERM_ID = ?");
        sql.append(" FETCH FIRST 1 ROW ONLY");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, normalizedPermitId.trim().toUpperCase(Locale.ROOT));
            if (masterId != null) statement.setLong(index++, masterId);
            if (permId != null) statement.setLong(index, permId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private Connection openConnection() throws SQLException {
        if (properties.getAtfmUrl() == null || properties.getAtfmUrl().isBlank()
                || properties.getAtfmUsername() == null || properties.getAtfmUsername().isBlank()) {
            throw new IllegalStateException("ATFM connection is not configured");
        }
        return DriverManager.getConnection(properties.getAtfmUrl(), properties.getAtfmUsername(),
                properties.getAtfmPassword());
    }
}
