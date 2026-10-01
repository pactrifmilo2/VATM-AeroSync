package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.ScheduleFlight;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class PermitRemarkPolicyTest {
    private ScheduleFlight flight(String purpose, String craft) {
        return new ScheduleFlight(purpose, 1L, null, "SV123", null, "1234567",
                "OERK", "VHHH", "0100", "0200", "A1",
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), null, craft);
    }

    @Test
    void newFlightAddsOnlySpecialPurposeAndMultipleCraft() {
        for (String purpose : new String[]{"CAR", "FER", "TECH", "AMB", "VIP"}) {
            assertThat(PermitRemarkPolicy.newFlight(flight(purpose, "A321"))).isNull();
            assertThat(PermitRemarkPolicy.newFlight(flight(purpose, "A321/A320")))
                    .isEqualTo("A321/A320");
        }
        assertThat(PermitRemarkPolicy.newFlight(flight("PAX", "A321"))).isNull();
        assertThat(PermitRemarkPolicy.newFlight(flight("PAX", "A321/A320")))
                .isEqualTo("A321/A320");
    }

    @Test
    void revisionsUseTheSelectedOldAndNewFormats() {
        var date = LocalDate.of(2026, 9, 24);
        var flight = flight("CAR", "A321/A320");
        assertThat(PermitRemarkPolicy.suddenRevision(flight, date, 7))
                .isEqualTo("EM 24092026 R(7) A321/A320");
        assertThat(PermitRemarkPolicy.seasonalOld(flight, "LD-00079/A/S/CHK/2026"))
                .isEqualTo("HUY REF LD-00079/A/S A321/A320");
        assertThat(PermitRemarkPolicy.seasonalNew(flight,
                "LD 00079AR7/S/CHK/2026", "LD-00079/A/S/CHK/2026"))
                .isEqualTo("EM LAY LD 00079AR7/S REF LD-00079/A/S A321/A320");
    }
}
