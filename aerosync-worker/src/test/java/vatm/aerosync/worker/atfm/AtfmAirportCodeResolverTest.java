package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtfmAirportCodeResolverTest {

    private final AtfmAirportCodeResolver resolver = new AtfmAirportCodeResolver();
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:atfm-airports-" + System.nanoTime() + ";MODE=Oracle");
        connection.createStatement().execute("""
                CREATE TABLE M_AERO (
                    AE_IATA VARCHAR2(3),
                    AE_CODE VARCHAR2(4)
                )
                """);
        connection.createStatement().execute("""
                INSERT INTO M_AERO (AE_IATA, AE_CODE) VALUES
                    ('HAN', 'VVNB'),
                    ('SGN', 'VVTS'),
                    ('PQC', 'VVPQ'),
                    ('ICN', 'RKSI')
                """);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void resolve_mapsThreeLetterIataCodeToFourLetterAtfmCode() throws Exception {
        assertThat(resolver.resolve(connection, "han")).isEqualTo("VVNB");
        assertThat(resolver.resolve(connection, " SGN ")).isEqualTo("VVTS");
        assertThat(resolver.resolve(connection, "PQC")).isEqualTo("VVPQ");
        assertThat(resolver.resolve(connection, "ICN")).isEqualTo("RKSI");
    }

    @Test
    void resolve_preservesAnExistingFourLetterCode() throws Exception {
        assertThat(resolver.resolve(connection, "wsss")).isEqualTo("WSSS");
    }

    @Test
    void resolve_rejectsMissingIataMapping() {
        assertThatThrownBy(() -> resolver.resolve(connection, "XXX"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("M_AERO.AE_IATA=XXX");
    }

    @Test
    void resolve_rejectsAmbiguousIataMapping() throws Exception {
        connection.createStatement().execute(
                "INSERT INTO M_AERO (AE_IATA, AE_CODE) VALUES ('HAN', 'XXXX')");

        assertThatThrownBy(() -> resolver.resolve(connection, "HAN"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Ambiguous M_AERO airport mapping");
    }
}
