package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerStepTest {

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
}
