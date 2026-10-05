package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class October45MissingFormatProfilesTest {
    @Test
    void readsNewProfilesAndKeepsSourceDataErrorsExplicit() throws Exception {
        String configured = System.getProperty("permit.october45.profile.files");
        Assumptions.assumeTrue(configured != null,
                "Set -Dpermit.october45.profile.files to audit the October 4/5 Word samples");
        Map<String, Expected> expected = expected();
        var reader = new WordPermitDocumentReader();
        var detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        var parser = new DocxSchedulePermitParser();
        for (String pathText : configured.split("\\|")) {
            Path path = Path.of(pathText);
            Expected target = expected.get(path.getFileName().toString());
            assertThat(target).as(path.toString()).isNotNull();
            var document = reader.read(path);
            assertThat(detector.detect(document, path.getFileName().toString()).id())
                    .as(path.toString()).isEqualTo(target.profile());
            if (target.error() == null) {
                var permit = parser.parse(document, path.getFileName().toString());
                assertThat(permit.operatorId()).isEqualTo(target.operator());
                assertThat(permit.flights()).hasSize(target.flights());
            } else {
                Throwable error = org.assertj.core.api.Assertions.catchThrowable(
                        () -> parser.parse(document, path.getFileName().toString()));
                assertThat(error).isNotNull().hasMessageContaining(target.error());
            }
        }
    }

    private Map<String, Expected> expected() {
        Map<String, Expected> result = new LinkedHashMap<>();
        add(result, "giangpth_20261005_094344_email_001_OF-71_A_W_2026VN_CJX_632fb691.doc",
                "cjx-english-seasonal-overflight-issued", "CJX", 2, null);
        add(result, "giangpth_20261005_095119_email_001_OF-73_A_W_2026VN_APZ_a560933e.doc",
                "apz-english-seasonal-overflight-issued", "APZ", 2, null);
        add(result, "giangpth_20261005_110907_email_001_OF-69_A_W_2026VN_CJX_c7942fa3.doc",
                "cjx-english-seasonal-overflight-issued", "CJX", 2, null);
        add(result, "giangpth_20261005_113235_email_001_LD-2456.RVS.05OCT26_b6c9449a.docx",
                "cao-english-seasonal-landing-revision", "CAO", 0,
                "Schedule row 1 has invalid ETD format; detected ETD = 22:80");
        add(result, "haanhlv_20261005_084423_email_001_OF-7246-LXJ969_07OCT26_48dadf0d.docx",
                "lxj-english-overflight-issued", "LXJ", 1, null);
        add(result, "haanhlv_20261005_085932_email_002_OF-75.A.W.2026VN.ACA_271835a1.docx",
                "aca-english-seasonal-overflight-issued", "ACA", 16, null);
        add(result, "haanhlv_20261005_111224_email_002_OF-75.A.W.2026VN.ACA_corr_bd1a75ec.docx",
                "aca-english-seasonal-overflight-issued", "ACA", 16, null);
        add(result, "haanhlv_20261005_095120_email_004_OF-77.A.W.2026VN.DTA_a286072f.docx",
                "dta-english-seasonal-overflight-issued", "DTA", 2, null);
        add(result, "thanhntm_20261005_144241_email_000_OF-7293_86b7a309.docx",
                "mjets-english-overflight-issued", "KXP", 1, null);
        add(result, "haanhlv_20261005_110632_email_quarantine_002_LD-3588__REV1__d2a87189.docx",
                "ld-3588-english-landing-revision", "PRV", 1, null);
        return result;
    }

    private void add(Map<String, Expected> result, String file, String profile,
                     String operator, int flights, String error) {
        result.put(file, new Expected(profile, operator, flights, error));
    }

    private record Expected(String profile, String operator, int flights, String error) {
    }
}
