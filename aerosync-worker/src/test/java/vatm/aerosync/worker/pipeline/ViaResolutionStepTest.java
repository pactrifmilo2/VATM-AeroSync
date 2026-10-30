package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.atfm.AtfmAirportCodeResolver;
import vatm.aerosync.worker.atfm.AtfmViaResolver;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ViaResolutionStepTest {

    private ViaResolutionStep step;

    @BeforeEach
    void setUp() throws Exception {
        AtfmDatabaseProperties properties = new AtfmDatabaseProperties();
        properties.setUrl("jdbc:h2:mem:via-step-" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
        properties.setUsername("sa");
        properties.setPassword("");
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword())) {
            connection.createStatement().execute("""
                    CREATE TABLE M_AERO (
                        AE_IATA VARCHAR2(3),
                        AE_CODE VARCHAR2(4)
                    )
                    """);
            connection.createStatement().execute("""
                    INSERT INTO M_AERO (AE_IATA, AE_CODE) VALUES
                        ('SGN', 'VVTS'),
                        ('VCL', 'VVCA'),
                        ('HAN', 'VVNB'),
                        ('KUL', 'WMKK'),
                        ('HKG', 'VHHH')
                    """);
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
                        ('VVTS', 'VVCA', 'Q2/W12/W1/W11', 'VJC'),
                        ('VVTS', 'VVCA', 'Q2/Q7/Q1/W11/W1', 'HVN')
                    """);
            connection.createStatement().execute("""
                    INSERT INTO M_AIRPORT_ROUTE (ID, FROM_AIRP, TO_AIRP, ROUTE, SUMMARY) VALUES
                        (1, 'VVTS', 'VVCA', 'Q2/Q7/Q1/W11/W1', 1)
                    """);
        }
        step = new ViaResolutionStep(
                properties,
                new AtfmAirportCodeResolver(),
                new AtfmViaResolver());
    }

    @Test
    void noReplacementMustNotDropUnreadableNewRouteAndProceedWithDeletion() {
        ProcessingContext context = context("HVN", false);
        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst()
                .withResolvedRoute("SGN", "VCL", "NOTE: SEE ATTACHED ROUTE");
        var revision = new SchedulePermit("LD-123/9/2026VN/REV1", "LD 00123/CHK/2026", "123",
                "CHK", "LD", "A", null, LocalDate.of(2026, 9, 24), "HVN", null, 24, null, "NO",
                false, true, false, "REV1", List.of(flight), List.of(flight));
        context.setSchedulePermit(revision);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> step.resolveAllowingMissingForNonSeasonal(context))
                .isInstanceOf(vatm.aerosync.worker.atfm.AtfmDocumentRouteException.class);
    }

    @Test
    void resolve_mapsIataAirportsAndAddsTheHvnRouteFromMVia() {
        ProcessingContext context = context();

        step.resolve(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.fromAirport()).isEqualTo("VVTS");
        assertThat(flight.toAirport()).isEqualTo("VVCA");
        assertThat(flight.via()).isEqualTo("Q2/Q7/Q1/W11/W1");
    }

    @Test
    void resolve_selectsTheFirstRouteWhenNoOperatorRouteMatches() {
        ProcessingContext context = context("XXX", true);

        step.resolve(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.fromAirport()).isEqualTo("VVTS");
        assertThat(flight.toAirport()).isEqualTo("VVCA");
        assertThat(flight.via()).isEqualTo("Q2/Q7/Q1/W11/W1");
    }

    @Test
    void resolve_writesDetailedErrorWhenNoDatabaseRouteExists() {
        ProcessingContext context = context("HVN", false);
        ScheduleFlight source = context.getSchedulePermit().flights().getFirst();
        context.setSchedulePermit(context.getSchedulePermit().withFlights(List.of(
                source.withResolvedRoute("SGN", "VVNB", null))));

        step.resolveAllowingMissingForNonSeasonal(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.fromAirport()).isEqualTo("VVTS");
        assertThat(flight.toAirport()).isEqualTo("VVNB");
        assertThat(flight.via()).isEqualTo(
                "Other permitted airways published in Viet Nams AIP/");
        assertThat(context.getRowValidationErrors()).extracting("code")
                .containsExactly("BR-ROUTE-NOT-FOUND");
        new BusinessRuleValidatorStep().validate(context);
    }

    @Test
    void resolve_usesVvvvAndWarningWhenAirportIsMissingFromAtfm() {
        ProcessingContext context = context("HVN", false);
        ScheduleFlight source = context.getSchedulePermit().flights().getFirst();
        context.setSchedulePermit(context.getSchedulePermit().withFlights(List.of(
                source.withResolvedRoute("XXX", "VCL", null))));

        step.resolve(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.fromAirport()).isEqualTo("VVVV");
        assertThat(flight.toAirport()).isEqualTo("VVCA");
        assertThat(context.getRowValidationErrors())
                .extracting("code", "field", "value")
                .contains(org.assertj.core.groups.Tuple.tuple(
                        "BR-AIRPORT-FALLBACK", "fromAirport", "XXX"));
        new BusinessRuleValidatorStep().validate(context);
    }

    @Test
    void resolve_keepsRowsWhenNoDatabaseRouteExistsForRegularPermit() {
        ProcessingContext context = context("HVN", false);
        ScheduleFlight source = context.getSchedulePermit().flights().getFirst();
        context.setSchedulePermit(context.getSchedulePermit().withFlights(List.of(
                source.withResolvedRoute("SGN", "VVNB", null))));

        step.resolve(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.fromAirport()).isEqualTo("VVTS");
        assertThat(flight.toAirport()).isEqualTo("VVNB");
        assertThat(flight.via()).isEqualTo(
                "Other permitted airways published in Viet Nams AIP/");
        assertThat(context.getRowValidationErrors()).extracting("code")
                .containsExactly("BR-ROUTE-NOT-FOUND");
    }

    @Test
    void resolve_keepsBlankViaAndWarningForDocumentRouteNotes() {
        ProcessingContext context = context("HVN", true);
        ScheduleFlight source = context.getSchedulePermit().flights().getFirst();
        context.setSchedulePermit(context.getSchedulePermit().withFlights(List.of(
                source.withResolvedRoute("SGN", "VCL", "NOTE: SEE ATTACHED ROUTE"))));

        step.resolveAllowingMissingForNonSeasonal(context);

        assertThat(context.getSchedulePermit().flights().getFirst().via())
                .startsWith("Other permitted airways published in Viet Nams AIP/");
        assertThat(context.getRowValidationErrors())
                .extracting("code", "field")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "BR-ROUTE-DOCUMENT-UNREADABLE", "via"));
        new BusinessRuleValidatorStep().validate(context);
    }

    @Test
    void resolve_allowsSeasonalPermitWithUnreadableDocumentRoute() {
        ProcessingContext context = context("HVN", true);
        ScheduleFlight source = context.getSchedulePermit().flights().getFirst()
                .withResolvedRoute("SGN", "VCL", "REMARK: ROUTE TO BE ADVISED");
        context.setSchedulePermit(new SchedulePermit(
                "LD-11111/S/2026VN", "LD 11111/S/CHK/2026", "11111",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 7, 1),
                "HVN", null, 24, "Vietnam Airlines", "SC",
                false, false, false, "raw", List.of(source), List.of()));

        step.resolve(context);

        assertThat(context.getSchedulePermit().flights().getFirst().via()).isNull();
        assertThat(context.getRowValidationErrors()).extracting("code")
                .containsExactly("BR-ROUTE-DOCUMENT-UNREADABLE");
    }

    @Test
    void resolve_warnsForSeasonalLandingTransitAfterAirportIataIsResolved() {
        ProcessingContext context = context("HVN", true);
        ScheduleFlight source = context.getSchedulePermit().flights().getFirst()
                .withResolvedRoute("WMKK", "VHHH", "M765");
        context.setSchedulePermit(new SchedulePermit(
                "LD-11111/A/W/2026VN", "LD 11111A/W/CHK/2026", "11111",
                "CHK", "LD", "A", "W", LocalDate.of(2026, 7, 1),
                "HVN", null, 24, null, "SC", false, true, false,
                "raw", List.of(source), List.of()));

        step.resolve(context);

        assertThat(context.getSchedulePermit().flights()).hasSize(1);
        assertThat(context.getSchedulePermit().flights().getFirst().fromAirport()).isEqualTo("WMKK");
        assertThat(context.getSchedulePermit().flights().getFirst().toAirport()).isEqualTo("VHHH");
        assertThat(context.getWarnings()).extracting("rowNumber", "message")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        1, "Chuyến bay này là chuyến bay quá cảnh"));
        assertThat(context.getRowValidationErrors()).isEmpty();
    }

    @Test
    void resolve_doesNotWarnForNonSeasonalLandingTransit() {
        ProcessingContext context = context("HVN", true);
        ScheduleFlight source = context.getSchedulePermit().flights().getFirst()
                .withResolvedRoute("WMKK", "VHHH", "M765");
        context.setSchedulePermit(new SchedulePermit(
                "LD-11111/A/2026VN", "LD 11111A/CHK/2026", "11111",
                "CHK", "LD", "A", null, LocalDate.of(2026, 7, 1),
                "HVN", null, 24, null, "NO", false, true, false,
                "raw", List.of(source), List.of()));

        step.resolve(context);

        assertThat(context.getWarnings()).isEmpty();
    }

    private ProcessingContext context() {
        return context("HVN", true);
    }

    private ProcessingContext context(String operatorId, boolean emptyAirwaysAllowed) {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, "LD 2517 HVN.docx", "h", FileSourceType.EMAIL, false));
        ScheduleFlight flight = new ScheduleFlight(
                "PAX", 1L, null, "VN1466", null, "0000500",
                "SGN", "VCL", "0905", "1030", null,
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 3),
                "Bay sớm", "321/320");
        context.setSchedulePermit(new SchedulePermit(
                "LD-11111/7/2026VN", "LD 11111/S/CHK/2026", "11111",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 7, 1),
                operatorId, null, 24, "200 Phố Nguyễn Sơn", "NO",
                true, emptyAirwaysAllowed, false, "raw", List.of(flight)));
        return context;
    }
}
