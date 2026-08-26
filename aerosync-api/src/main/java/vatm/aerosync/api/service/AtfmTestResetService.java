package vatm.aerosync.api.service;

import org.springframework.stereotype.Service;
import vatm.aerosync.api.config.TestReplayProperties;
import vatm.aerosync.common.entity.PermitImport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
public class AtfmTestResetService {

    private static final String AEROSYNC_USER = "AEROSYNC";

    private final TestReplayProperties properties;

    public AtfmTestResetService(TestReplayProperties properties) {
        this.properties = properties;
    }

    public TargetDeleteResult deleteOwnedPermit(PermitImport permitImport) {
        if (permitImport.getTargetMasterId() == null || permitImport.getTargetPermId() == null) {
            return new TargetDeleteResult(0, 0);
        }
        validateConfiguration();

        try (Connection connection = DriverManager.getConnection(
                properties.getAtfmUrl(), properties.getAtfmUsername(), properties.getAtfmPassword())) {
            connection.setAutoCommit(false);
            try {
                TargetMatch target = targetOwnership(connection, permitImport);
                if (target.ownership() == TargetOwnership.MISSING) {
                    connection.commit();
                    return new TargetDeleteResult(0, 0);
                }
                if (target.ownership() == TargetOwnership.UNSAFE) {
                    connection.rollback();
                    throw new IllegalStateException(
                            "Refusing to delete the ATFM permit because the recorded target row "
                                    + "changed or was not written by AEROSYNC");
                }
                int details = deleteDetails(connection, permitImport.getTargetPermId(), target.table());
                int masters = deleteMaster(connection, permitImport, target.table());
                if (masters != 1) {
                    connection.rollback();
                    throw new IllegalStateException("ATFM permit changed while the test replay was being prepared");
                }
                connection.commit();
                return new TargetDeleteResult(masters, details);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not reset the ATFM permit for test replay: "
                    + exception.getMessage(), exception);
        }
    }

    private TargetMatch targetOwnership(Connection connection, PermitImport permitImport) throws SQLException {
        TargetMatch sc = targetOwnership(connection, permitImport, TableKind.SC);
        if (sc.ownership() != TargetOwnership.MISSING) {
            return sc;
        }
        TargetMatch no = targetOwnership(connection, permitImport, TableKind.NO);
        if (no.ownership() != TargetOwnership.MISSING) {
            return no;
        }
        return normalizedPermitExists(connection, permitImport.getNormalizedPermitId())
                ? new TargetMatch(TargetOwnership.UNSAFE, TableKind.SC)
                : new TargetMatch(TargetOwnership.MISSING, TableKind.SC);
    }

    private TargetMatch targetOwnership(Connection connection, PermitImport permitImport,
                                         TableKind table) throws SQLException {
        String masterTable = table == TableKind.SC ? "T_PERMMASTER_SC" : "T_PERMMASTER_NO";
        String sql = "SELECT PERMNBR_ID, LASTUSER FROM " + masterTable
                + " WHERE ID = ? AND PERM_ID = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, permitImport.getTargetMasterId());
            statement.setLong(2, permitImport.getTargetPermId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String lastUser = resultSet.getString("LASTUSER");
                    boolean owned = samePermitIdentity(permitImport.getNormalizedPermitId(),
                            resultSet.getString("PERMNBR_ID"))
                            && lastUser != null && AEROSYNC_USER.equalsIgnoreCase(lastUser.trim());
                    return new TargetMatch(owned ? TargetOwnership.OWNED : TargetOwnership.UNSAFE, table);
                }
            }
        }
        return new TargetMatch(TargetOwnership.MISSING, table);
    }

    private boolean normalizedPermitExists(Connection connection, String normalizedPermitId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM T_PERMMASTER_SC WHERE PERMNBR_ID = ?")) {
            statement.setString(1, normalizedPermitId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private int deleteDetails(Connection connection, long permitId, TableKind table) throws SQLException {
        String detailTable = table == TableKind.SC ? "T_PERMDETAIL_SC" : "T_PERMDETAIL_NO";
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + detailTable + " WHERE PERM_ID = ?")) {
            statement.setLong(1, permitId);
            return statement.executeUpdate();
        }
    }

    private int deleteMaster(Connection connection, PermitImport permitImport, TableKind table) throws SQLException {
        String masterTable = table == TableKind.SC ? "T_PERMMASTER_SC" : "T_PERMMASTER_NO";
        String sql = "DELETE FROM " + masterTable
                + " WHERE ID = ? AND PERM_ID = ?"
                + " AND (PERMNBR_ID = ? OR PERMNBR_ID = ? OR PERMNBR_ID = ?)"
                + " AND UPPER(TRIM(LASTUSER)) = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, permitImport.getTargetMasterId());
            statement.setLong(2, permitImport.getTargetPermId());
            String expected = permitImport.getNormalizedPermitId();
            statement.setString(3, expected);
            statement.setString(4, expected.replace("/S/", "/"));
            statement.setString(5, expected.replace("/W/", "/"));
            statement.setString(6, AEROSYNC_USER);
            return statement.executeUpdate();
        }
    }

    private void validateConfiguration() {
        if (properties.getAtfmUrl() == null || properties.getAtfmUrl().isBlank()
                || properties.getAtfmUsername() == null || properties.getAtfmUsername().isBlank()) {
            throw new IllegalStateException("ATFM connection is not configured for test replay");
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    public record TargetDeleteResult(int masterRows, int detailRows) {
    }

    private enum TargetOwnership {
        OWNED,
        MISSING,
        UNSAFE
    }

    /** Accept the legacy target spelling that omitted the seasonal /S/ or /W/ segment. */
    private boolean samePermitIdentity(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return canonicalPermit(expected).equals(canonicalPermit(actual));
    }

    private String canonicalPermit(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("/(?:S|W)/", "/");
    }

    private enum TableKind { SC, NO }

    private record TargetMatch(TargetOwnership ownership, TableKind table) {
    }
}
