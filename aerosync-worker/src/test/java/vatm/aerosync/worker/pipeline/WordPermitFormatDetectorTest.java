package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.exception.FormatValidationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WordPermitFormatDetectorTest {

    private final WordPermitFormatDetector detector =
            new WordPermitFormatDetector(new DocxPermitProfileCatalog());

    @Test
    void carrierDocumentWithoutMatchingYamlFailsInsteadOfUsingCaav() {
        WordPermitDocument document = document("""
                1. Carrier/Operator
                IATA code: C6 | ICAO code: MFX
                3. Schedules: UTC Time
                4. Purpose of Flight(s): Cargo
                Flight number | Effective from | Effective to
                MFX7632 | 11AUG26 | 11AUG26
                """);

        assertThatThrownBy(() -> detector.detect(document, "OF-MFX7632-11AUG26.docx"))
                .isInstanceOf(FormatValidationException.class)
                .hasMessageContaining("File chưa có định dạng YAML phù hợp");
    }

    @Test
    void carrierProfileWinsOverCaavWhenIssuedPermitIdentityExists() {
        WordPermitDocument document = document("""
                OF-5716/8/2026VN
                IATA code: C6 | ICAO code: MFX
                3. Schedules: UTC Time
                4. Purpose of Flight(s): Cargo
                Flight number | Effective from | Effective to
                MFX7632 | 11AUG26 | 11AUG26
                """);

        assertThat(detector.detect(document, "OF-5716-MFX7632.docx").id())
                .isEqualTo("mfx-english-overflight-issued");
    }

    private WordPermitDocument document(String rawContent) {
        return new WordPermitDocument(rawContent, rawContent, rawContent, List.of(), List.of());
    }
}
