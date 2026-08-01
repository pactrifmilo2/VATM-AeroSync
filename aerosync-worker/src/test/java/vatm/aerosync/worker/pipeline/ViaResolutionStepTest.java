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
                        ('VCL', 'VVCA')
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
                    INSERT INTO M_VIA (FROM_AIRP, TO_AIRP, VIA, OPER) VALUES
                        ('VVTS', 'VVCA', 'Q2/W12/W1/W11', 'VJC'),
                        ('VVTS', 'VVCA', 'Q2/Q7/Q1/W11/W1', 'HVN')
                    """);
        }
        step = new ViaResolutionStep(
                properties,
                new AtfmAirportCodeResolver(),
                new AtfmViaResolver());
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
    void resolve_allowsAnEmptyRouteWhenTheProfileExplicitlyAllowsIt() {
        ProcessingContext context = context("XXX", true);

        step.resolve(context);

        ScheduleFlight flight = context.getSchedulePermit().flights().getFirst();
        assertThat(flight.fromAirport()).isEqualTo("VVTS");
        assertThat(flight.toAirport()).isEqualTo("VVCA");
        assertThat(flight.via()).isNull();
    }

    private ProcessingContext context() {
        return context("HVN", true);
    }

    private ProcessingContext context(String operatorId, boolean emptyAirwaysAllowed) {
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                1L, "LD 2517 HVN.docx", "h", FileSourceType.EMAIL, false));
        ScheduleFlight flight = new ScheduleFlight(
                "PAX", 1L, null, "VN1466", null, "0000100",
                "SGN", "VCL", "0905", "1030", null,
                LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 4),
                "Bay sớm", "321/320");
        context.setSchedulePermit(new SchedulePermit(
                "LD-11111/7/2026VN", "LD 11111/S/CHK/2026", "11111",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 7, 1),
                operatorId, null, 24, "200 Phố Nguyễn Sơn", "NO",
                true, emptyAirwaysAllowed, false, "raw", List.of(flight)));
        return context;
    }
}
