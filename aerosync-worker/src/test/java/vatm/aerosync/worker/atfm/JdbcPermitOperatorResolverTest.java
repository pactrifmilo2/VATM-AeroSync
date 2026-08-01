package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPermitOperatorResolverTest {

    private JdbcPermitOperatorResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        AtfmDatabaseProperties properties = new AtfmDatabaseProperties();
        properties.setUrl("jdbc:h2:mem:operator-resolver-" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        properties.setUsername("sa");
        properties.setPassword("");
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword())) {
            connection.createStatement().execute("""
                    CREATE TABLE M_OPER (
                        OPER_IATA VARCHAR2(2),
                        OPER_ICAO VARCHAR2(3),
                        OPER_NAME VARCHAR2(255)
                    )
                    """);
            connection.createStatement().execute("""
                    INSERT INTO M_OPER (OPER_IATA, OPER_ICAO, OPER_NAME) VALUES
                        ('VN', 'HVN', 'VIETNAM AIRLINES'),
                        ('NN', 'AAC', 'ASIA AIR CHARTER'),
                        ('NN', 'AIR', 'AIRFLITE INC'),
                        ('NN', 'PFA', 'PACIFIC FLIGHT SERVICES PTY LTD'),
                        ('NN', NULL, 'PRIVATE OWNER')
                    """);
        }
        resolver = new JdbcPermitOperatorResolver(properties);
    }

    @Test
    void resolve_usesTheOnlyIcaoWithoutRequiringCarrierName() {
        assertThat(resolver.resolve("VN", null)).contains("HVN");
    }

    @Test
    void resolve_matchesCarrierNameWhenIataHasMultipleIcaoCodes() {
        assertThat(resolver.resolve("NN", "Pacific Flight Services"))
                .contains("PFA");
        assertThat(resolver.resolve("NN", "AIRFLITE INC."))
                .contains("AIR");
    }

    @Test
    void resolve_returnsPrivateForMatchedRowWithoutValidIcao() {
        assertThat(resolver.resolve("NN", "PRIVATE OWNER"))
                .contains("PRV");
    }

    @Test
    void resolve_doesNotChooseTheFirstIcaoWhenCarrierNameIsMissingOrUnknown() {
        assertThat(resolver.resolve("NN", null)).isEmpty();
        assertThat(resolver.resolve("NN", "UNKNOWN OPERATOR")).isEmpty();
    }
}
