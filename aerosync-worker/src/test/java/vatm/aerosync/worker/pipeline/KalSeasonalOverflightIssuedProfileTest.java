package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.worker.model.SchedulePermit;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KalSeasonalOverflightIssuedProfileTest {

    @Test
    void detectsAndParsesKoreanAirWinterPermit() throws Exception {
        String configured = System.getProperty(
                "permit.kal.seasonal.sample.path",
                "D:/vatm-storage/error/2026/10/25/"
                        + "local_20261025_000850_fs_local_20261024_103223_fs_giangpth_20261023_184224_"
                        + "email_001_OF-105_B_W_2026VN_KAL_0fd4e899_ba0c203b_8aca5fb0.docx");
        Path file = Path.of(configured);
        Assumptions.assumeTrue(Files.isRegularFile(file),
                "Configured KAL seasonal permit document does not exist");

        WordPermitDocument document = new WordPermitDocumentReader().read(file);
        DocxPermitFormatProfile profile = new WordPermitFormatDetector(
                new DocxPermitProfileCatalog()).detect(document, file.getFileName().toString());
        SchedulePermit permit = new DocxSchedulePermitParser().parse(
                document, file.getFileName().toString());

        assertThat(profile.id()).isEqualTo("kal-seasonal-overflight-issued");
        assertThat(permit.sourcePermitNumber()).isEqualTo("OF-1051/B/W/2026VN");
        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 1051B/W/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("KAL");
        assertThat(permit.permitDate()).hasToString("2026-10-21");
        assertThat(permit.reviewOnly()).isFalse();
        assertThat(permit.flights()).hasSize(17);
        assertThat(permit.flights().getFirst().flightNumber()).isEqualTo("KAL341");
        assertThat(permit.flights().getFirst().via()).contains("N892", "M765");
        assertThat(permit.flights().getFirst().sourceAircraftType()).contains("B748");
    }
}
