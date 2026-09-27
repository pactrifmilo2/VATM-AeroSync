package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import vatm.aerosync.api.config.TestReplayProperties;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;

class AtfmDatabaseStateGatewayTest {
    @Test
    void classifiesLifecycleBeforeOpeningAtfmConnection() {
        TestReplayProperties properties = new TestReplayProperties();
        AtfmDatabaseStateGateway gateway = new AtfmDatabaseStateGateway(properties);
        assertThat(gateway.state(null, null, null, false, false)).isEqualTo(DatabaseState.PENDING);
        assertThat(gateway.state(null, null, null, true, true)).isEqualTo(DatabaseState.ERROR);
        assertThat(gateway.state(null, null, null, true, false)).isEqualTo(DatabaseState.UNKNOWN);
        assertThat(gateway.state(1L, 2L, "OF-1", true, false)).isEqualTo(DatabaseState.UNKNOWN);
    }

    @Test
    void verifiesFoundAndMissingAgainstBothAtfmStores() throws Exception {
        String url = "jdbc:h2:mem:atfm-report;MODE=Oracle;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE T_PERMMASTER_SC (ID BIGINT, PERM_ID BIGINT, PERMNBR_ID VARCHAR(100))");
            statement.execute("CREATE TABLE T_PERMMASTER_NO (ID BIGINT, PERM_ID BIGINT, PERMNBR_ID VARCHAR(100))");
            statement.execute("INSERT INTO T_PERMMASTER_NO VALUES (10, 20, 'O/F 001/S/CHK/2026')");
        }
        TestReplayProperties properties = new TestReplayProperties();
        properties.setAtfmUrl(url); properties.setAtfmUsername("sa"); properties.setAtfmPassword("");
        AtfmDatabaseStateGateway gateway = new AtfmDatabaseStateGateway(properties);
        var states = gateway.states(List.of(
                new AtfmDatabaseStateGateway.Candidate(1L, 10L, 20L, "o/f 001/s/chk/2026", true, false),
                new AtfmDatabaseStateGateway.Candidate(2L, 99L, 88L, "O/F 404/S/CHK/2026", true, false),
                new AtfmDatabaseStateGateway.Candidate(3L, null, null, null, false, false),
                new AtfmDatabaseStateGateway.Candidate(4L, null, null, null, true, true)));
        assertThat(states).containsEntry(1L, DatabaseState.FOUND)
                .containsEntry(2L, DatabaseState.MISSING)
                .containsEntry(3L, DatabaseState.PENDING)
                .containsEntry(4L, DatabaseState.ERROR);
    }

    @Test
    void serializesContractValuesInLowerCase() {
        assertThat(DatabaseState.FOUND.jsonValue()).isEqualTo("found");
        assertThat(DatabaseState.UNKNOWN.jsonValue()).isEqualTo("unknown");
    }
}
