package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class Spa070PermitProfileRegressionTest {

    @Test
    void parse_mapsHighlightedMetadataAndOnlyNewScheduleFromRealDocument() {
        Path sample = Path.of("..",
                "dungdm_20260729_172132_email_007__SPA070__REV1_LD-2702_S26_04AUG-07AUG_ND.docx")
                .toAbsolutePath().normalize();

        SchedulePermit permit = new DocxSchedulePermitParser()
                .parse(sample, sample.getFileName().toString());

        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-2702/07/2026VN/REV1");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD-2702/07/2026");
        assertThat(permit.reference()).isEqualTo("LD-2702/07/2026VN");
        assertThat(permit.operatorId()).isEqualTo("SPQ");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(permit.billingAddress()).contains("Sun Grand City");
        assertThat(permit.flights()).hasSize(4);

        assertFlight(permit, 0, "9G824", "SGN", "0125", "HAN", "0335", 4);
        assertFlight(permit, 1, "9G824", "SGN", "0125", "HAN", "0335", 6);
        assertFlight(permit, 2, "9G824", "SGN", "0125", "HAN", "0335", 7);
        assertFlight(permit, 3, "9G841", "HAN", "0420", "SGN", "0630", 4);
    }

    private void assertFlight(SchedulePermit permit, int index, String number,
                              String from, String etd, String to, String eta, int day) {
        assertThat(permit.flights().get(index)).satisfies(flight -> {
            assertThat(flight.flightNumber()).isEqualTo(number);
            assertThat(flight.fromAirport()).isEqualTo(from);
            assertThat(flight.etd()).isEqualTo(etd);
            assertThat(flight.toAirport()).isEqualTo(to);
            assertThat(flight.eta()).isEqualTo(eta);
            assertThat(flight.beginDate()).isEqualTo(LocalDate.of(2026, 8, day));
            assertThat(flight.sourceAircraftType()).isEqualTo("A321");
        });
    }
}
