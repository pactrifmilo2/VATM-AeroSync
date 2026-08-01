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
                TargetOwnership ownership = targetOwnership(connection, permitImport);
                if (ownership == TargetOwnership.MISSING) {
                    connection.commit();
                    return new TargetDeleteResult(0, 0);
                }
                if (ownership == TargetOwnership.UNSAFE) {
                    connection.rollback();
                    throw new IllegalStateException(
                            "Refusing to delete the ATFM permit because the recorded target row "
                                    + "changed or was not written by AEROSYNC");
                }
                int details = deleteDetails(connection, permitImport.getTargetPermId());
                int masters = deleteMaster(connection, permitImport);
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

    private TargetOwnership targetOwnership(Connection connection, PermitImport permitImport) throws SQLException {
        String sql = """
                SELECT PERMNBR_ID, LASTUSER
                  FROM T_PERMMASTER_SC
                 WHERE ID = ? AND PERM_ID = ?
                 FOR UPDATE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, permitImport.getTargetMasterId());
            statement.setLong(2, permitImport.getTargetPermId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String lastUser = resultSet.getString("LASTUSER");
                    boolean owned = permitImport.getNormalizedPermitId().equals(resultSet.getString("PERMNBR_ID"))
                            && lastUser != null && AEROSYNC_USER.equalsIgnoreCase(lastUser.trim());
                    return owned ? TargetOwnership.OWNED : TargetOwnership.UNSAFE;
                }
            }
        }
        return normalizedPermitExists(connection, permitImport.getNormalizedPermitId())
                ? TargetOwnership.UNSAFE
                : TargetOwnership.MISSING;
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

    private int deleteDetails(Connection connection, long permitId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM T_PERMDETAIL_SC WHERE PERM_ID = ?")) {
            statement.setLong(1, permitId);
            return statement.executeUpdate();
        }
    }

    private int deleteMaster(Connection connection, PermitImport permitImport) throws SQLException {
        String sql = """
                DELETE FROM T_PERMMASTER_SC
                 WHERE ID = ? AND PERM_ID = ? AND PERMNBR_ID = ? AND UPPER(TRIM(LASTUSER)) = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, permitImport.getTargetMasterId());
            statement.setLong(2, permitImport.getTargetPermId());
            statement.setString(3, permitImport.getNormalizedPermitId());
            statement.setString(4, AEROSYNC_USER);
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
}
