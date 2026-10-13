package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the CLX (Cargolux) seasonal CAAV overflight profile to the real permit
 * received on 13/10/2026 so future shared-profile changes cannot silently
 * break or re-target this carrier.
 */
class October13CargoluxProfileTest {

    private static final String FILE_NAME =
            "haanhlv_20261013_103551_email_005_OF-93.A.W.2026VN.CLX_7287ce4f.docx";

    @Test
    void parsesCargoluxSeasonalOverflightPermit() throws Exception {
        String directory = System.getProperty("permit.october13.corpus.dir", "");
        Assumptions.assumeTrue(!directory.isBlank(),
                "Set -Dpermit.october13.corpus.dir to the 13/10/2026 Word corpus");

        Path file = Path.of(directory).resolve(FILE_NAME);
        Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(file),
                "Sample Word permit not present in the configured corpus");

        var document = new WordPermitDocumentReader().read(file);
        var profile = new WordPermitFormatDetector(new DocxPermitProfileCatalog())
                .detect(document, file.getFileName().toString());
        assertThat(profile.id()).isEqualTo("clx-caav-seasonal-overflight-issued");

        var permit = new DocxSchedulePermitParser().parse(document, file.getFileName().toString());
        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 0093A/W/CHK/2026");
        assertThat(permit.operatorId()).isEqualTo("CLX");
        assertThat(permit.flights()).hasSize(66);
        assertThat(permit.reviewOnly()).isTrue();
        assertThat(permit.flights())
                .allSatisfy(flight -> assertThat(flight.purposeId()).isEqualTo("CAR"));
        assertThat(permit.flights())
                .allSatisfy(flight -> {
                    assertThat(flight.fromAirport()).matches("^[A-Z]{4}$");
                    assertThat(flight.toAirport()).matches("^[A-Z]{4}$");
                    assertThat(flight.fromAirport()).isNotEqualTo(flight.toAirport());
                });
    }
}
