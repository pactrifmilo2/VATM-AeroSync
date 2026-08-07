package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerStepTest {

    @Test
    void springContext_usesCatalogConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(PermitOperatorCatalog.class, NormalizerStep.class);
            context.refresh();

            assertThat(context.getBean(NormalizerStep.class)).isNotNull();
        }
    }

    @Test
    void normalize_trimsAndUppercasesFields() {
        NormalizerStep step = new NormalizerStep(ZoneId.of("UTC"));
        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(1L, "/tmp/f.csv", "h", FileSourceType.FILESYSTEM, false));
        context.getRows().add(new FlightRow("  vn123 ", " han ", " sgn ", LocalDate.of(2026, 6, 1)));

        step.normalize(context);

        FlightRow row = context.getRows().getFirst();
        assertThat(row.getCallsign()).isEqualTo("VN123");
        assertThat(row.getFrom()).isEqualTo("HAN");
        assertThat(row.getTo()).isEqualTo("SGN");
    }

    @Test
    void normalize_convertsPermitFlightIataPrefixBeforeDatabaseWrite() {
        NormalizerStep step = new NormalizerStep(
                ZoneId.of("UTC"), new PermitOperatorCatalog());
        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(1L, "/tmp/permit.docx", "h", FileSourceType.EMAIL, false));
        ScheduleFlight first = flight("VN1822");
        ScheduleFlight second = flight("VN7158");
        context.setSchedulePermit(new SchedulePermit(
                "LD-2493/7/2026VN", "LD 02493/S/CHK/2026", "2493",
                "CHK", "LD", "A", "S", LocalDate.of(2026, 7, 2),
                "HVN", null, 24, null, "NO", "raw", List.of(first, second)));

        step.normalize(context);

        assertThat(context.getSchedulePermit().flights())
                .extracting(ScheduleFlight::flightNumber)
                .containsExactly("HVN1822", "HVN7158");
    }

    @Test
    void normalize_preservesCallsignWhenIataAndIcaoAreUnavailable() {
        NormalizerStep step = new NormalizerStep(
                ZoneId.of("UTC"), new PermitOperatorCatalog());
        ProcessingContext context = permitContext(
                "PRV",
                "Name: DEER JET CO., LTD\nIATA code: N/A\nICAO code: N/A",
                "B8415");

        step.normalize(context);

        assertThat(context.getSchedulePermit().flights())
                .extracting(ScheduleFlight::flightNumber)
                .containsExactly("B8415");
    }

    @Test
    void normalize_preservesCallsignWhenOperatorLabelsAreAbsent() {
        NormalizerStep step = new NormalizerStep(
                ZoneId.of("UTC"), new PermitOperatorCatalog());
        ProcessingContext context = permitContext(
                "PRV", "Private non-scheduled flight", "B8415");

        step.normalize(context);

        assertThat(context.getSchedulePermit().flights())
                .extracting(ScheduleFlight::flightNumber)
                .containsExactly("B8415");
    }

    @Test
    void normalize_stillUsesPrvForAnUnresolvedRealIataCode() {
        NormalizerStep step = new NormalizerStep(
                ZoneId.of("UTC"), new PermitOperatorCatalog());
        ProcessingContext context = permitContext(
                "PRV", "IATA code: NN\nICAO code:", "NN123");

        step.normalize(context);

        assertThat(context.getSchedulePermit().flights())
                .extracting(ScheduleFlight::flightNumber)
                .containsExactly("PRV123");
    }

    private ProcessingContext permitContext(String operatorId,
                                             String rawContent,
                                             String flightNumber) {
        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(1L, "/tmp/permit.docx", "h", FileSourceType.EMAIL, false));
        context.setSchedulePermit(new SchedulePermit(
                "OF-5592/08/2026VN", "O/F 05592/S/CHK/2026", "5592",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 8, 4),
                operatorId, null, 72, null, "NO", rawContent,
                List.of(flight(flightNumber))));
        return context;
    }

    private ScheduleFlight flight(String flightNumber) {
        return new ScheduleFlight(
                "PAX", 249L, BigDecimal.ZERO, flightNumber, null, "0004000",
                "VVPQ", "VVTS", "0235", "0345", null,
                LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 2), null);
    }
}
