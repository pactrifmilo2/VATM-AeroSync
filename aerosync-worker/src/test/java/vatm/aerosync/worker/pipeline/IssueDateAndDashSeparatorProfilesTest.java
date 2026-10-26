package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for three CAAV permits reported on 26/10/2026:
 *
 * <ul>
 *   <li>OF_118_A/B_W_2026VN_CQH.doc — permit date printed as
 *       "23rd Octorber 2026" (ordinal suffix plus a misspelled month).</li>
 *   <li>OF-37A-W26-JST-PAX-VSS.doc — permit number printed as
 *       "OF – 37/A/W/2026VN" with an en dash instead of a hyphen.</li>
 * </ul>
 */
class IssueDateAndDashSeparatorProfilesTest {

    @Test
    void readsCqhIssueDateWithOrdinalSuffixAndMisspelledMonth() throws Exception {
        Path directory = configuredDirectory();
        Assumptions.assumeTrue(directory != null,
                "Set -Dpermit.issue-date.dir to the folder holding the CQH and JST samples");

        var reader = new WordPermitDocumentReader();
        var parser = new DocxSchedulePermitParser();

        var first = parser.parse(reader.read(directory.resolve("OF_118_A_W_2026VN_CQH.doc")),
                "OF_118_A_W_2026VN_CQH.doc");
        assertThat(first.operatorId()).isEqualTo("CQH");
        assertThat(first.permitDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(first.flights()).hasSize(4);
        assertThat(first.flights().getFirst().flightNumber()).isEqualTo("CQH8893");
        assertThat(first.flights().getFirst().etd()).isEqualTo("0855");
        assertThat(first.flights().getFirst().beginDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(first.flights().getFirst().endDate()).isEqualTo(LocalDate.of(2027, 3, 27));

        var second = parser.parse(reader.read(directory.resolve("OF_118_B_W_2026VN_CQH.doc")),
                "OF_118_B_W_2026VN_CQH.doc");
        assertThat(second.operatorId()).isEqualTo("CQH");
        assertThat(second.permitDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(second.flights()).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    void detectsJetstarPermitNumberWrittenWithAnEnDash() throws Exception {
        Path directory = configuredDirectory();
        Assumptions.assumeTrue(directory != null,
                "Set -Dpermit.issue-date.dir to the folder holding the CQH and JST samples");

        Path source = directory.resolve("OF-37A-W26-JST-PAX-VSS.doc");
        var reader = new WordPermitDocumentReader();
        var detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        var document = reader.read(source);

        var profile = detector.detect(document, source.getFileName().toString());
        assertThat(profile.id()).isEqualTo("jst-english-seasonal-overflight-issued");

        var permit = new DocxSchedulePermitParser().parse(document, source.getFileName().toString());
        assertThat(permit.operatorId()).isEqualTo("JST");
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(permit.normalizedPermitId()).isEqualTo("O/F 0037A/W/CHK/2026");
        assertThat(permit.flights()).hasSize(8);
        assertThat(permit.flights().getFirst().flightNumber()).isEqualTo("JST29");
        assertThat(permit.flights().getFirst().fromAirport()).isEqualTo("YMML");
        assertThat(permit.flights().getFirst().etd()).isEqualTo("0335");
        assertThat(permit.flights().getFirst().eta()).isEqualTo("1245");
        assertThat(permit.flights().getFirst().beginDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(permit.flights().getFirst().endDate()).isEqualTo(LocalDate.of(2027, 3, 27));
    }

    private Path configuredDirectory() {
        String configured = System.getProperty("permit.issue-date.dir", "");
        if (configured.isBlank()) {
            return null;
        }
        Path directory = Path.of(configured);
        return Files.isDirectory(directory) ? directory : null;
    }
}
