package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import vatm.aerosync.api.config.TestReplayProperties;
import vatm.aerosync.common.entity.PermitImport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtfmTestResetServiceTest {

    @Test
    void deleteOwnedPermit_deletesDetailsThenAerosyncMaster() throws Exception {
        TestDatabase database = database();
        database.insertPermit("AEROSYNC");
        PermitImport permitImport = permitImport();

        var result = new AtfmTestResetService(database.properties()).deleteOwnedPermit(permitImport);

        assertThat(result.masterRows()).isEqualTo(1);
        assertThat(result.detailRows()).isEqualTo(2);
        assertThat(database.count("T_PERMMASTER_SC")).isZero();
        assertThat(database.count("T_PERMDETAIL_SC")).isZero();
    }

    @Test
    void deleteOwnedPermit_refusesRowsNotWrittenByAerosync() throws Exception {
        TestDatabase database = database();
        database.insertPermit("MANUAL_USER");

        assertThatThrownBy(() -> new AtfmTestResetService(database.properties())
                .deleteOwnedPermit(permitImport()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not written by AEROSYNC");

        assertThat(database.count("T_PERMMASTER_SC")).isEqualTo(1);
        assertThat(database.count("T_PERMDETAIL_SC")).isEqualTo(2);
    }

    @Test
    void deleteOwnedPermit_allowsRecoveryWhenTargetWasAlreadyRemoved() throws Exception {
        TestDatabase database = database();

        var result = new AtfmTestResetService(database.properties()).deleteOwnedPermit(permitImport());

        assertThat(result.masterRows()).isZero();
        assertThat(result.detailRows()).isZero();
    }

    private PermitImport permitImport() {
        PermitImport permitImport = new PermitImport();
        permitImport.setNormalizedPermitId("LD-06/A/S/2026");
        permitImport.setTargetMasterId(10L);
        permitImport.setTargetPermId(20L);
        return permitImport;
    }

    private TestDatabase database() throws Exception {
        String url = "jdbc:h2:mem:atfm-reset-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE T_PERMMASTER_SC (
                        ID BIGINT PRIMARY KEY,
                        PERM_ID BIGINT NOT NULL UNIQUE,
                        PERMNBR_ID VARCHAR(100) NOT NULL,
                        LASTUSER VARCHAR(100) NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE T_PERMDETAIL_SC (
                        ID BIGINT PRIMARY KEY,
                        PERM_ID BIGINT NOT NULL,
                        CONSTRAINT FK_TEST_PERMIT FOREIGN KEY (PERM_ID)
                            REFERENCES T_PERMMASTER_SC(PERM_ID))
                    """);
        }
        TestReplayProperties properties = new TestReplayProperties();
        properties.setAtfmUrl(url);
        properties.setAtfmUsername("sa");
        properties.setAtfmPassword("");
        return new TestDatabase(url, properties);
    }

    private record TestDatabase(String url, TestReplayProperties properties) {

        void insertPermit(String lastUser) throws Exception {
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO T_PERMMASTER_SC VALUES "
                        + "(10, 20, 'LD-06/A/S/2026', '" + lastUser + "')");
                statement.executeUpdate("INSERT INTO T_PERMDETAIL_SC VALUES (1, 20)");
                statement.executeUpdate("INSERT INTO T_PERMDETAIL_SC VALUES (2, 20)");
            }
        }

        int count(String table) throws Exception {
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                 Statement statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
