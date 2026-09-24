package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.model.ScheduleFlight;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewPermitStoragePolicyTest {
    @Test
    void checksBothMasterTablesWithoutLoadingFlightDetails() throws Exception {
        var properties = new AtfmDatabaseProperties();
        properties.setUrl("jdbc:h2:mem:new-permit-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        properties.setUsername("sa");
        properties.setPassword("");
        var gateway = new JdbcAtfmScheduleGateway(properties, new AtfmAirportCodeResolver());
        var permit = mock(SchedulePermit.class);
        when(permit.atfmTargetPermitId()).thenReturn("O/F 0031A/W/CHK/2026");
        try (var connection = DriverManager.getConnection(properties.getUrl(), "sa", "")) {
            for (String table : List.of("T_PERMMASTER_SC", "T_PERMMASTER_NO")) {
                connection.createStatement().execute("CREATE TABLE " + table
                        + " (ID BIGINT, PERM_ID BIGINT, PERMNBR_ID VARCHAR(100))");
            }
            assertThat(gateway.findNewPermitInEitherStore(permit)).isEmpty();
            for (String table : List.of("T_PERMMASTER_SC", "T_PERMMASTER_NO")) {
                connection.createStatement().execute("INSERT INTO " + table
                        + " VALUES(1,2,'O/F 0031A/W/CHK/2026')");
                assertThat(gateway.findNewPermitInEitherStore(permit)).isPresent();
                when(permit.atfmTargetPermitId()).thenReturn("O/F 0031A/W/CHK/2027");
                assertThat(gateway.findNewPermitInEitherStore(permit)).isEmpty();
                when(permit.atfmTargetPermitId()).thenReturn("O/F 0031A/W/CHK/2026");
                connection.createStatement().execute("DELETE FROM " + table);
            }
        }
    }

    @Test
    void allDetailBindingsWriteNullRegistration() throws Exception {
        var gateway = new JdbcAtfmScheduleGateway(new AtfmDatabaseProperties(), new AtfmAirportCodeResolver());
        var flight = new ScheduleFlight("PAX", 1L, null, "SVA1", "HZARA", "1234567",
                "OERK", "RPLL", "0100", "0200", "A1",
                LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25), "original remark");
        for (String name : List.of("bindDetail", "bindNoDetail")) {
            var statement = mock(PreparedStatement.class);
            var method = JdbcAtfmScheduleGateway.class.getDeclaredMethod(name,
                    PreparedStatement.class, long.class, ScheduleFlight.class);
            method.setAccessible(true);
            method.invoke(gateway, statement, 2L, flight);
            verify(statement).setNull(6, Types.VARCHAR);
        }

    }
}
