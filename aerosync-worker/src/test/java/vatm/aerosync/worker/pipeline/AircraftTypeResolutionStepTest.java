package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.atfm.AtfmAircraftTypeResolver;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AircraftTypeResolutionStepTest {

    private AircraftTypeResolutionStep step;

    @BeforeEach
    void setUp() throws Exception {
        AtfmDatabaseProperties properties = new AtfmDatabaseProperties();
        properties.setUrl("jdbc:h2:mem:aircraft-step-" + System.nanoTime() + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        properties.setUsername("sa");
        properties.setPassword("");
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
                        (6864, 'A33X', 'A33X', NULL, 230),
                        (1712, 'GLF6', NULL, NULL, 46),
                        (4102, 'GA6C', 'GA6C', NULL, 94600),
                        (4941, 'GA6C', 'GA6C', NULL, 42),
                        (9001, 'AMB', 'AMB', NULL, 10),
                        (9002, 'AMB', 'AMB', NULL, 20)
                    """);
        }
        step = new AircraftTypeResolutionStep(
                new AircraftTypeCatalog(),
                new AtfmAircraftTypeResolver(properties));
    }

    @Test
    void resolve_mapsTheConcatenatedThyAircraftListThroughItsAlias() {
        ProcessingContext context = context("A330-200F B777-200FB747-400F");

        step.resolve(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.sourceAircraftType()).isEqualTo("A330-200F B777-200FB747-400F");
        assertThat(flight.craftId()).isEqualTo(6864L);
        assertThat(flight.mtow()).isEqualByComparingTo(new BigDecimal("230"));
    }

    @Test
    void resolve_triesCompositeCandidatesInSourceOrder() {
        ProcessingContext context = context("UNKNOWN / GLF6");

        step.resolve(context);

        assertThat(context.getSchedulePermit().flights().getFirst().craftId()).isEqualTo(1712L);
    }

    @Test
    void resolve_quarantinesAmbiguousAtfmCodes() {
        ProcessingContext context = context("AMB");

        assertThatThrownBy(() -> step.resolve(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BR-AIRCRAFT-AMBIGUOUS")
                .hasMessageContaining("9001")
                .hasMessageContaining("9002");
        assertThat(context.getRowValidationErrors())
                .extracting("field", "code", "value")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "aircraftType", "BR-AIRCRAFT-AMBIGUOUS", "AMB"));
    }

    @Test
    void resolve_usesConfiguredPreferenceForObservedAmbiguousCode() {
        ProcessingContext context = context("GA6C");

        step.resolve(context);

        assertThat(context.getSchedulePermit().flights().getFirst().craftId()).isEqualTo(4102L);
    }

    @Test
    void resolve_quarantinesUnknownAircraftTypes() {
        ProcessingContext context = context("75V");

        assertThatThrownBy(() -> step.resolve(context))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BR-AIRCRAFT-NOT-FOUND")
                .hasMessageContaining("75V");
    }

    private ProcessingContext context(String sourceAircraftType) {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, "permit.docx", "h", FileSourceType.EMAIL, false));
        ScheduleFlight flight = new ScheduleFlight(
                "CAR", 0L, null, "THY001", null, "1000000",
                "LTFM", "VVNB", "0100", "1000", "A1",
                LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 27),
                "CAR " + sourceAircraftType, sourceAircraftType);
        context.setSchedulePermit(new SchedulePermit(
                "OF-4861/7/2026VN", "O/F 04861/S/CHK/2026", "4861",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 7, 27),
                "THY", null, 72, "Istanbul", "SC", "raw", List.of(flight)));
        return context;
    }
}
