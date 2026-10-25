package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HvnCaavSeasonalLandingIssuedProfileTest {

    @Test
    void detectsAndParsesVietnamAirlinesWinterPermit() throws Exception {
        String configured = System.getProperty(
                "permit.hvn.seasonal.sample.path",
                "D:/vatm-storage/error/2026/10/23/"
                        + "duongvtt_20261023_181013_email_001_LD-69.A.W.2026VN_VNA_a5ce4720.docx");
        Path file = Path.of(configured);
        Assumptions.assumeTrue(Files.isRegularFile(file),
                "Configured HVN seasonal permit document does not exist");

        WordPermitDocument document = new WordPermitDocumentReader().read(file);
        DocxPermitFormatProfile profile = new WordPermitFormatDetector(
                new DocxPermitProfileCatalog()).detect(document, file.getFileName().toString());
        SchedulePermit permit = new DocxSchedulePermitParser().parse(
                document, file.getFileName().toString());

        assertThat(profile.id()).isEqualTo("hvn-caav-seasonal-landing-issued");
        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-690/A/W/2026VN");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD 0690A/W/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("HVN");
        assertThat(permit.permitDate()).hasToString("2026-10-23");
        assertThat(permit.reviewOnly()).isFalse();
        assertThat(permit.flights()).hasSizeGreaterThan(500);
        assertThat(permit.flights().getFirst().flightNumber()).isEqualTo("HVN10");
    }

    @Test
    void detectsAndParsesTest2VietnamAirlinesPermitIncludingEta() throws Exception {
        String configured = System.getProperty(
                "permit.hvn.test2.sample.path",
                "D:/vatm-storage/processed/2026/10/25/TEST_2.docx");
        Path file = Path.of(configured);
        Assumptions.assumeTrue(Files.isRegularFile(file),
                "Configured TEST_2 Vietnam Airlines permit document does not exist");

        WordPermitDocument document = new WordPermitDocumentReader().read(file);
        DocxPermitFormatProfile profile = new WordPermitFormatDetector(
                new DocxPermitProfileCatalog()).detect(document, file.getFileName().toString());
        SchedulePermit permit = new DocxSchedulePermitParser().parse(
                document, file.getFileName().toString());

        assertThat(profile.id()).isEqualTo("hvn-caav-seasonal-landing-issued");
        assertThat(permit.sourcePermitNumber()).isEqualTo("LD-6916/A/W/2026VN");
        assertThat(permit.normalizedPermitId()).isEqualTo("LD 6916A/W/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("HVN");
        assertThat(permit.permitDate()).hasToString("2026-10-23");
        assertThat(permit.reviewOnly()).isFalse();
        assertThat(permit.flights()).hasSize(1037);
        assertThat(permit.flights())
                .filteredOn(flight -> flight.flightNumber().equals("HVN10"))
                .singleElement()
                .satisfies(flight -> assertThat(flight.eta()).isEqualTo("2230"));
        assertThat(permit.flights())
                .filteredOn(flight -> flight.flightNumber().equals("HVN11"))
                .singleElement()
                .satisfies(flight -> assertThat(flight.eta()).isEqualTo("0555+"));
    }
}
