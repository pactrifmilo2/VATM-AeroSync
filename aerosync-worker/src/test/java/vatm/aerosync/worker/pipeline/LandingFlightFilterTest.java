package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LandingFlightFilterTest {
    private ScheduleFlight flight(String number, String from, String to) {
        return new ScheduleFlight("PAX", 1, null, number, null, "1234567", from, to,
                "0100", "0300", "A1", LocalDate.of(2026,9,24),
                LocalDate.of(2026,9,24), null);
    }

    private SchedulePermit permit(String source, String type, List<ScheduleFlight> flights) {
        return new SchedulePermit(source, "LD 03454/CHK/2026", "3454", "CHK", type,
                "A", "S", LocalDate.of(2026,9,24), "BAV", null, 24, null,
                "NO", "raw", flights);
    }

    @Test
    void onlyAdHocLandingRoutesWithVietnamAirportAreWritten() {
        var source = permit("LD-3454/09/2026VN", "LD", List.of(
                flight("BAV1", "OERK", "RPLL"), flight("BAV2", "VVTS", "RPLL"),
                flight("BAV3", "RPLL", "VVTS"), flight("BAV4", "VVTS", "VVNB")));
        assertThat(LandingFlightFilter.filter(source).flights())
                .extracting(ScheduleFlight::flightNumber)
                .containsExactly("BAV2", "BAV3", "BAV4");
        assertThatThrownBy(() -> LandingFlightFilter.filter(permit("LD-3455/09/2026VN",
                "LD", List.of(flight("BAV1", "OERK", "RPLL")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no flight with a Viet Nam airport");
    }

    @Test
    void seasonalLandingAndOverflightRemainUnfiltered() {
        var foreign = List.of(flight("BAV1", "OERK", "RPLL"));
        assertThat(LandingFlightFilter.filter(permit("LD-31/A/S/2026VN", "LD", foreign))
                .flights()).hasSize(1);
        assertThat(LandingFlightFilter.filter(permit("OF-3454/09/2026VN", "O/F", foreign))
                .flights()).hasSize(1);
    }
}
