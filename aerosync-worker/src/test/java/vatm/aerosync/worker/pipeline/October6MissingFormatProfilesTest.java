package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.worker.model.ProcessingContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class October6MissingFormatProfilesTest {
    @Test
    void readsSwmNcaEsrSamplesAndPreservesNcaSourceDateError() throws Exception {
        String configured = System.getProperty("permit.october6.profile.files");
        Assumptions.assumeTrue(configured != null,
                "Set -Dpermit.october6.profile.files to audit SWM/NCA/ESR samples");
        Map<String, Expected> expected = new LinkedHashMap<>();
        expected.put("duongvtt_20261006_084521_email_000_OF-70.A.W.2026VN-SWM_4b4261f2.docx",
                new Expected("swm-english-seasonal-overflight-issued", "SWM", 6, null));
        expected.put("giangpth_20261006_100528_email_001_OF-72_A_W_2026VN_NCA_0e18d7e2.doc",
                new Expected("nca-english-seasonal-overflight-issued", "NCA", 6, "BR-EFFECTIVE-DATES"));
        expected.put("giangpth_20261006_103848_email_001_OF-78_A_W_2026VN_ESR_d921e80e.doc",
                new Expected("esr-vietnamese-seasonal-overflight-issued", "ESR", 12, null));
        expected.put("giangpth_20261006_115455_email_001_OF-78_A_W_2026VN_ESR_f75e923e.doc",
                new Expected("esr-vietnamese-seasonal-overflight-issued", "ESR", 12, null));

        var reader = new WordPermitDocumentReader();
        var detector = new WordPermitFormatDetector(new DocxPermitProfileCatalog());
        var parser = new DocxSchedulePermitParser();
        for (String pathText : configured.split("\\|")) {
            Path path = Path.of(pathText);
            Expected target = expected.get(path.getFileName().toString());
            assertThat(target).as(path.toString()).isNotNull();
            var document = reader.read(path);
            assertThat(detector.detect(document, path.getFileName().toString()).id())
                    .isEqualTo(target.profile());
            if (target.error() == null) {
                var permit = parser.parse(document, path.getFileName().toString());
                assertThat(permit.operatorId()).isEqualTo(target.operator());
                assertThat(permit.flights()).hasSize(target.flights());
            } else {
                var permit = parser.parse(document, path.getFileName().toString());
                assertThat(permit.operatorId()).isEqualTo(target.operator());
                assertThat(permit.flights()).hasSize(target.flights());
                var context = new ProcessingContext(null);
                context.setSchedulePermit(permit);
                var validation = org.assertj.core.api.Assertions.catchThrowable(
                        () -> new BusinessRuleValidatorStep().validate(context));
                assertThat(validation)
                        .isInstanceOf(BusinessRuleException.class)
                        .hasMessageContaining(target.error())
                        .hasMessageContaining("Effective from: 2026-10-25 - Effective to: 2026-03-27");
            }
        }
    }

    private record Expected(String profile, String operator, int flights, String error) {
    }
}
