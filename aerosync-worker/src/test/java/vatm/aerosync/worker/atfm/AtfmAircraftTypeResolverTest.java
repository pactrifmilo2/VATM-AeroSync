package vatm.aerosync.worker.atfm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtfmAircraftTypeResolverTest {

    private AtfmDatabaseProperties properties;
    private AtfmAircraftTypeResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AtfmDatabaseProperties();
        properties.setUrl("jdbc:h2:mem:atfm-aircraft-" + System.nanoTime() + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        properties.setUsername("sa");
        properties.setPassword("");
        properties.setAircraftCacheTtlSeconds(300);
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword())) {
            connection.createStatement().execute("""
                    CREATE TABLE M_CRAFT_TYPE (
                        CRAFT_ID NUMBER,
                        MA VARCHAR2(4),
                        SOHIEU VARCHAR2(3),
                        SOHIEU2 VARCHAR2(20),
                        TAITRONG NUMBER
                    )
                    """);
            connection.createStatement().execute("""
                    INSERT INTO M_CRAFT_TYPE (CRAFT_ID, MA, SOHIEU, SOHIEU2, TAITRONG) VALUES
                        (249, 'B738', '738', 'Boeing 738', 0),
                        (1631, 'B788', '788', 'Boeing 788', 0),
                        (1226, 'B77W', '77W', 'Boeing 77W', 0),
                        (6765, 'B77Y', '77W', 'Boeing 77W', 0),
                        (4082, 'B77X', '77X', 'Boeing 77X', 0),
                        (6801, 'B77X', '77X', 'Boeing 77X', 0),
                        (6484, 'A32X', '32X', 'Airbus 32X', 0),
                        (6485, 'A32X', '32X', 'Airbus 32X', 0),
                        (6481, 'B73Y', '73Y', 'Boeing 73Y', 0),
                        (6482, 'B73Y', '73Y', 'Boeing 73Y', 0)
                    """);
        }
        resolver = new AtfmAircraftTypeResolver(
                properties,
                Clock.fixed(Instant.parse("2026-07-29T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void resolve_readsCraftIdAndMtowFromAtfm() {
        AtfmAircraftTypeResolver.ResolvedAircraft aircraft = resolver.resolve(List.of("B738"));

        assertThat(aircraft.craftId()).isEqualTo(249L);
        assertThat(aircraft.mtow()).isZero();
        assertThat(aircraft.matchedCode()).isEqualTo("B738");
    }

    @Test
    void resolve_readsIataFromSohieuAndIcaoFromMa() {
        assertThat(resolver.resolve(List.of("B738")).craftId()).isEqualTo(249L);
        assertThat(resolver.resolve(List.of("738")).craftId()).isEqualTo(249L);
    }

    @Test
    void resolve_usesTheFirstCandidateThatExists() {
        assertThat(resolver.resolve(List.of("UNKNOWN", "B788")).craftId()).isEqualTo(1631L);
    }

    @Test
    void resolve_rejectsAmbiguousDatabaseCodes() {
        assertThatThrownBy(() -> resolver.resolve(List.of("77W")))
                .isInstanceOf(AtfmAircraftTypeResolver.AmbiguousAircraftTypeException.class)
                .hasMessageContaining("1226")
                .hasMessageContaining("6765");
    }

    @Test
    void resolve_allowsAnAliasToTargetAUniqueMaCode() {
        assertThat(resolver.resolve(List.of("B77W")).craftId()).isEqualTo(1226L);
    }

    @Test
    void resolve_usesAnApprovedPreferenceForKnownDuplicateRows() {
        AtfmAircraftTypeResolver.ResolvedAircraft aircraft = resolver.resolve(List.of("B77X"));

        assertThat(aircraft.craftId()).isEqualTo(4082L);
        assertThat(aircraft.mtow()).isZero();
    }

    @Test
    void resolve_usesApprovedPreferencesForObservedDuplicateRows() {
        assertThat(resolver.resolve(List.of("A32X")).craftId()).isEqualTo(6484L);
        assertThat(resolver.resolve(List.of("73Y")).craftId()).isEqualTo(6481L);
    }

    @Test
    void resolve_rejectsUnknownCodes() {
        assertThatThrownBy(() -> resolver.resolve(List.of("75V")))
                .isInstanceOf(AtfmAircraftTypeResolver.AircraftTypeNotFoundException.class)
                .hasMessageContaining("75V");
    }

    @Test
    void resolve_reusesTheCachedSnapshot() throws Exception {
        assertThat(resolver.resolve(List.of("B738")).craftId()).isEqualTo(249L);
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO M_CRAFT_TYPE (CRAFT_ID, MA, SOHIEU, SOHIEU2, TAITRONG)
                    VALUES (5441, 'DF7', 'DF7', 'Dassault 7', 0)
                    """);
        }

        assertThatThrownBy(() -> resolver.resolve(List.of("DF7")))
                .isInstanceOf(AtfmAircraftTypeResolver.AircraftTypeNotFoundException.class);
    }
}
