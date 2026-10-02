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

    @Test
    void resolve_keepsAircraftInheritedFromAtfmBaselineWithoutSourceType() {
        ScheduleFlight inherited = new ScheduleFlight(
                "PAX", 4366L, java.math.BigDecimal.valueOf(93.5), "SPQ907", null,
                "1234567", "VVNB", "VVDN", "2350", "0120+", null,
                java.time.LocalDate.of(2026, 9, 5), java.time.LocalDate.of(2026, 9, 10),
                "HUY TU LD-32/C/S/2026VN", null);
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, "permit.docx", "h", FileSourceType.EMAIL, false));
        context.setSchedulePermit(new SchedulePermit(
                "LD-32/C/S/2026VN/REV17", "LD 0032C/S/CHK/2026", "32C",
                "CHK", "LD", "C", "S", LocalDate.of(2026, 9, 5),
                "SPQ", "LD-32/C/S/2026VN", 24, "SPQ", "SC",
                false, false, false, "REV17", List.of(inherited), List.of()));

        step.resolve(context);

        assertThat(context.getSchedulePermit().flights().getFirst().craftId()).isEqualTo(4366L);
    }

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
                    CREATE TABLE M_CRAFT (
                        ID NUMBER,
                        CRAFT_ICAO VARCHAR2(4),
                        CRAFT_IATA VARCHAR2(3),
                        CRAFT_NAME VARCHAR2(100)
                    )
                    """);
            connection.createStatement().execute("""
                    INSERT INTO M_CRAFT (ID, CRAFT_ICAO, CRAFT_IATA, CRAFT_NAME) VALUES
                        (6864, 'A33X', NULL, 'Airbus 33X'),
                        (6865, 'A321', NULL, 'Airbus 321'),
                        (6866, 'A320', NULL, 'Airbus 320'),
                        (6867, 'A333', NULL, 'Airbus 333'),
                        (6868, 'B768', '768', 'Boeing 768'),
                        (1712, 'GLF6', NULL, 'Gulfstream 6'),
                        (4102, 'GA6C', NULL, 'Gulfstream 6C'),
                        (4941, 'GA6C', NULL, 'Gulfstream 6C'),
                        (9001, 'XAMB', 'AMB', 'Ambiguous'),
                        (9002, 'YAMB', 'AMB', 'Ambiguous'),
                        (6881, 'A000', NULL, 'Unknown aircraft')
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
        assertThat(flight.sourceAircraftType()).isEqualTo("A330-200F/B777-200F/B747-400F");
        assertThat(flight.craftId()).isEqualTo(6864L);
        assertThat(flight.mtow()).isZero();
    }

    @Test
    void resolve_triesCompositeCandidatesInSourceOrder() {
        ProcessingContext context = context("UNKNOWN / GLF6");

        step.resolve(context);

        assertThat(context.getSchedulePermit().flights().getFirst().craftId()).isEqualTo(1712L);
    }

    @Test
    void resolve_usesA000AndReportsAmbiguousAtfmCodes() {
        ProcessingContext context = context("AMB");

        step.resolve(context);
        assertThat(context.getSchedulePermit().flights().getFirst().craftId()).isEqualTo(6881L);
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
    void resolve_usesA000AndReportsUnknownAircraftTypes() {
        ProcessingContext context = context("75V");

        step.resolve(context);
        assertThat(context.getSchedulePermit().flights().getFirst().craftId()).isEqualTo(6881L);
        assertThat(context.getRowValidationErrors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("BR-AIRCRAFT-NOT-FOUND");
            assertThat(error.message()).contains("75V");
        });
    }

    @Test
    void resolveAllowingMissingPrefixesAdHocNumericAircraftTypes() {
        ProcessingContext context = context("321");

        step.resolveAllowingMissingForNonSeasonal(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.sourceAircraftType()).isEqualTo("321");
        assertThat(flight.craftId()).isEqualTo(6865L);
        assertThat(context.getRowValidationErrors()).isEmpty();
    }

    @Test
    void resolve_normalizesEveryNumericAircraftInSeasonalRemark() {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, "permit.docx", "h", FileSourceType.EMAIL, false));
        ScheduleFlight flight = new ScheduleFlight(
                "PAX", 0L, null, "HVN001", null, "1000000",
                "VVNB", "VVTS", "0100", "0300", "W1",
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 30),
                "PAX 768/321/320/333", "768/321/320/333");
        context.setSchedulePermit(new SchedulePermit(
                "LD-4861/S/2026VN", "LD 04861/S/CHK/2026", "4861",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 9, 1),
                "HVN", null, 24, "Vietnam Airlines", "SC", "raw", List.of(flight)));

        step.resolveAllowingMissingForNonSeasonal(context);

        ScheduleFlight resolved = context.getSchedulePermit().flights().getFirst();
        assertThat(resolved.sourceAircraftType()).isEqualTo("768/321/320/333");
        assertThat(resolved.remark()).isEqualTo("PAX 768/321/320/333");
        assertThat(resolved.craftId()).isEqualTo(6868L);
    }

    @Test
    void resolve_usesSlashForHyphenSeparatedNumericAircraftList() {
        ProcessingContext context = context("768-321-320-333");

        step.resolveAllowingMissingForNonSeasonal(context);

        assertThat(context.getSchedulePermit().flights().getFirst().sourceAircraftType())
                .isEqualTo("768/321/320/333");
    }

    @Test
    void resolveAllowingMissingUsesFallbackCraftIdAndWarning() {
        ProcessingContext context = context("799");

        step.resolveAllowingMissingForNonSeasonal(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.sourceAircraftType()).isEqualTo("799");
        assertThat(flight.craftId()).isEqualTo(6881L);
        assertThat(context.getRowValidationErrors())
                .extracting("code")
                .containsExactly("BR-AIRCRAFT-NOT-FOUND");
    }

    @Test
    void resolveAllowingMissingHandlesNullAircraftTypeWithoutNullPointerException() {
        ProcessingContext context = context(null);

        step.resolveAllowingMissingForNonSeasonal(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.sourceAircraftType()).isNull();
        assertThat(flight.craftId()).isEqualTo(6881L);
        assertThat(context.getRowValidationErrors())
                .extracting("code", "field", "value")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "BR-AIRCRAFT-MISSING", "aircraftType", ""));
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
