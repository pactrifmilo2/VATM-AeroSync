package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtfmViaResolverTest {

    private final AtfmViaResolver resolver = new AtfmViaResolver();
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:atfm-vias-" + System.nanoTime() + ";MODE=Oracle");
        connection.createStatement().execute("""
                CREATE TABLE M_VIA (
                    VIA VARCHAR2(4000),
                    FROM_AIRP VARCHAR2(20),
                    TO_AIRP VARCHAR2(20),
                    OPER VARCHAR2(50)
                )
                """);
        connection.createStatement().execute("""
                INSERT INTO M_VIA (FROM_AIRP, TO_AIRP, VIA, OPER) VALUES
                    ('VVTS', 'VVCA', ' Q2/W12/W1/W11 ', 'VJC'),
                    ('VVTS', 'VVCA', ' Q2/Q7/Q1/W11/W1 ', 'HVN'),
                    ('VVCA', 'VVTS', ' W11/Q1/W1 ', 'HVN'),
                    ('VVNB', 'VVDN', ' Q1 ', NULL)
                """);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void resolve_usesFromToAndOperatorForTheHvnRoute() throws Exception {
        assertThat(resolver.resolve(connection, "vvts", "vvca", "hvn", null))
                .isEqualTo("Q2/Q7/Q1/W11/W1");
    }

    @Test
    void resolve_usesTheGenericRouteWhenNoOperatorRouteExists() throws Exception {
        assertThat(resolver.resolve(connection, "VVNB", "VVDN", "HVN", null))
                .isEqualTo("Q1");
    }

    @Test
    void resolve_preservesDocumentRouteWhenMViaHasNoRoute() throws Exception {
        assertThat(resolver.resolve(connection, "VVNB", "WSSS", "HVN", "M765/M771"))
                .isEqualTo("M765/M771");
    }

    @Test
    void resolve_prefersTheRouteWrittenInThePermitOverReferenceAlternatives() throws Exception {
        assertThat(resolver.resolve(connection, "VVTS", "VVCA", "XXX", " dct / q1 / "))
                .isEqualTo("DCT/Q1");
    }

    @Test
    void resolve_rejectsMissingRouteWhenTheDocumentAlsoHasNone() {
        assertThatThrownBy(() -> resolver.resolve(
                connection, "VVNB", "WSSS", "HVN", null))
                .isInstanceOf(AtfmReferenceDataException.class)
                .hasMessageContaining("M_VIA.FROM_AIRP=VVNB")
                .hasMessageContaining("TO_AIRP=WSSS");
    }

    @Test
    void resolve_rejectsAnAmbiguousPairWithoutAnOperatorMatch() {
        assertThatThrownBy(() -> resolver.resolve(
                connection, "VVTS", "VVCA", "XXX", null))
                .isInstanceOf(AtfmReferenceDataException.class)
                .hasMessageContaining("Ambiguous ATFM routes");
    }
}
