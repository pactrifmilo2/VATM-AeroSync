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
                        MA VARCHAR2(64),
                        SOHIEU VARCHAR2(64),
                        SOHIEU2 VARCHAR2(64),
                        TAITRONG NUMBER
                    )
                    """);
            connection.createStatement().execute("""
                    INSERT INTO M_CRAFT_TYPE (CRAFT_ID, MA, SOHIEU, SOHIEU2, TAITRONG) VALUES
                        (249, 'B738', '738', NULL, 79),
                        (1631, 'B788', '788', NULL, 219),
                        (1226, 'B77W', '77W', NULL, 351),
                        (6765, '77W', '77W', NULL, 12),
                        (4082, 'B77X', 'B77X', NULL, 347),
                        (6801, 'B77X', 'B77X', NULL, 45),
                        (6484, 'A32X', 'A32X', NULL, 12),
                        (6485, 'A32X', 'A32X', NULL, 12),
                        (6481, '73Y', '73Y', NULL, 15),
                        (6482, '73Y', '73Y', NULL, 15)
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
        assertThat(aircraft.mtow()).isEqualByComparingTo(new BigDecimal("79"));
        assertThat(aircraft.matchedCode()).isEqualTo("B738");
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
        assertThat(aircraft.mtow()).isEqualByComparingTo(new BigDecimal("347"));
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
                    VALUES (5441, 'DF7', 'DF7', NULL, 32)
                    """);
        }

        assertThatThrownBy(() -> resolver.resolve(List.of("DF7")))
                .isInstanceOf(AtfmAircraftTypeResolver.AircraftTypeNotFoundException.class);
    }
}
