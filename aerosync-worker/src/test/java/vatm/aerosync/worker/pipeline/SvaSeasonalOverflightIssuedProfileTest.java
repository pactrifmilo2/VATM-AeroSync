package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SvaSeasonalOverflightIssuedProfileTest {

    private final DocxSchedulePermitParser parser = new DocxSchedulePermitParser();

    @Test
    void parsesBothRealSvaWinterPermits() {
        assertPermit(
                "phepbaykhb_20260923_084650_email_000_OF_31_A_W_2026_SVA_78421f6d.doc",
                "OF-0031/A/W/2026VN", "O/F 0031A/W/CHK/2026", "0031A", "A", 16, "PAX", "G474/L628/R468/N500");
        assertPermit(
                "phepbaykhb_20260923_084651_email_001_OF_31_B_W_2026_SVA_6456d9c2.doc",
                "OF-0031/B/W/2026VN", "O/F 0031B/W/CHK/2026", "0031B", "B", 66, "CAR", "G474/Q1/W1/A1/A202/B214/W21/R474");
    }

    private void assertPermit(String fileName,
                              String sourcePermit,
                              String normalizedPermit,
                              String permitNumber,
                              String version,
                              int expectedFlights,
                              String purposeId,
                              String firstRoute) {
        Path file = Path.of("..").resolve(fileName).normalize();
        Assumptions.assumeTrue(Files.isRegularFile(file), "Real SVA permit sample is unavailable");

        SchedulePermit permit = parser.parse(file, fileName);

        assertThat(permit.sourcePermitNumber()).isEqualTo(sourcePermit);
        assertThat(permit.normalizedPermitId()).isEqualTo(normalizedPermit);
        assertThat(permit.permitNumber()).isEqualTo(permitNumber);
        assertThat(permit.version()).isEqualTo(version);
        assertThat(permit.season()).isEqualTo("W");
        assertThat(permit.operatorId()).isEqualTo("SVA");
        assertThat(permit.billingAddress()).isEqualTo("SAUDI ARABIAN AIRLINES (SAUDIA)");
        assertThat(permit.flights()).hasSize(expectedFlights);
        assertThat(permit.flights().getFirst().purposeId()).isEqualTo(purposeId);
        assertThat(permit.flights().getFirst().via()).isEqualTo(firstRoute);
        assertThat(permit.flights()).allSatisfy(flight -> {
            assertThat(flight.flightNumber()).startsWith("SVA");
            assertThat(flight.via()).isNotBlank();
        });
    }
}