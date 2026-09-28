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
            java.util.Set<Target> found = new java.util.HashSet<>();
            for (int offset = 0; offset < lookups.size(); offset += 500) {
                java.util.List<Candidate> batch = lookups.subList(offset, Math.min(offset + 500, lookups.size()));
                for (String table : MASTER_TABLES) found.addAll(findTargets(connection, table, batch));
            }
            for (Candidate candidate : lookups) {
                result.put(candidate.key(), found.contains(Target.from(candidate))
                        ? DatabaseState.FOUND : DatabaseState.MISSING);
            }
        } catch (SQLException | RuntimeException exception) {
            lookups.forEach(candidate -> result.put(candidate.key(), DatabaseState.UNKNOWN));
        }
        return result;
    }

    private java.util.Set<Target> findTargets(Connection connection, String table,
                                               java.util.List<Candidate> candidates) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(candidates.size(), "?"));
        String sql = "SELECT ID, PERM_ID, UPPER(TRIM(PERMNBR_ID)) AS PERMNBR_ID FROM " + table
                + " WHERE UPPER(TRIM(PERMNBR_ID)) IN (" + placeholders + ")";
        java.util.Set<Target> targets = new java.util.HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            int index = 1;
            for (Candidate candidate : candidates)
                statement.setString(index++, normalize(candidate.normalizedPermitId()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) targets.add(new Target(rows.getLong("ID"), rows.getLong("PERM_ID"),
                        rows.getString("PERMNBR_ID")));
            }
        }
        return targets;
    }

    private record Target(Long masterId, Long permId, String permitId) {
        static Target from(Candidate candidate) {
            return new Target(candidate.masterId(), candidate.permId(), normalize(candidate.normalizedPermitId()));
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
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
