package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

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
                CREATE TABLE M_AIRPORT_ROUTE (
                    ID NUMBER,
                    FROM_AIRP VARCHAR2(4),
                    TO_AIRP VARCHAR2(4),
                    ROUTE VARCHAR2(150),
                    SUMMARY NUMBER
                )
                """);
        connection.createStatement().execute("""
                INSERT INTO M_VIA (FROM_AIRP, TO_AIRP, VIA, OPER) VALUES
                    ('VVTS', 'VVCA', ' Q2/W12/W1/W11 ', 'VJC'),
                    ('VVTS', 'VVCA', ' Q2/Q7/Q1/W11/W1 ', 'HVN'),
                    ('VVCA', 'VVTS', ' W11/Q1/W1 ', 'HVN'),
                    ('VVNB', 'VVDN', ' Q1 ', NULL),
                    ('VHHH', 'WIII', ' N892 ', 'SKY'),
                    ('VHHH', 'WIII', ' L642/L644 ', 'SKY'),
                    ('VHHH', 'WIII', ' L642 ', 'SKY'),
                    ('VHHH', 'WIII', ' L642/G221/W12/W1/L644 ', 'SKY')
                """);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void resolve_rejectsLegacyViaRouteWhenAirportRouteCatalogIsMissing() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolve(connection, "vvts", "vvca", "hvn", null))
                .isInstanceOf(AtfmReferenceDataException.class)
                .hasMessageContaining("ATFM route not found");
    }

    @Test
    void resolve_prefersAirportRouteCatalogWhenDocumentRouteIsMissing() throws Exception {
        connection.createStatement().execute("""
                INSERT INTO M_AIRPORT_ROUTE (ID, FROM_AIRP, TO_AIRP, ROUTE, SUMMARY)
                VALUES (1, 'VVTS', 'VVCA', 'W1/A206', 10)
                """);

        assertThat(resolver.resolve(connection, "VVTS", "VVCA", "HVN", null))
                .isEqualTo("W1/A206");
    }

    @Test
    void resolve_rejectsGenericLegacyRouteWhenAirportRouteCatalogIsMissing() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolve(connection, "VVNB", "VVDN", "HVN", null))
                .isInstanceOf(AtfmReferenceDataException.class)
                .hasMessageContaining("ATFM route not found");
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
    void resolve_normalizesHyphenSeparatedDocumentRoutes() throws Exception {
        assertThat(resolver.resolve(connection, "VVTS", "VVCA", "HVN", "M765-M771"))
                .isEqualTo("M765/M771");
    }

    @Test
    void resolve_rejectsNotesReadFromTheDocumentRouteCell() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolve(connection, "VVTS", "VVCA", "HVN",
                                "NOTE: SUBJECT TO ATC APPROVAL"))
                .isInstanceOf(AtfmDocumentRouteException.class)
                .hasMessageContaining("BR-ROUTE-DOCUMENT-UNREADABLE");
    }

    @Test
    void resolve_rejectsWhenTheDocumentAndDatabaseHaveNoRoute() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolve(connection, "VVNB", "WSSS", "HVN", null))
                .isInstanceOf(AtfmReferenceDataException.class)
                .hasMessageContaining("ATFM route not found");
    }

    @Test
    void resolve_rejectsMultipleLegacyRoutesWithoutAirportRouteCatalog() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolve(connection, "VHHH", "WIII", "SKY", null))
                .isInstanceOf(AtfmReferenceDataException.class)
                .hasMessageContaining("ATFM route not found");
    }

    @Test
    void resolve_rejectsWhenAirportRouteCatalogHasNoMatchingRoute() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolve(connection, "VVTS", "VVCA", "XXX", null))
                .isInstanceOf(AtfmReferenceDataException.class)
                .hasMessageContaining("ATFM route not found");
    }
}
