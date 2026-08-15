package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WordFormatRequirementTest {

    @Test
    void marksOnlyWordDocumentsWhoseYamlFormatIsMissing() {
        assertThat(WordFormatRequirement.isRequired(
                "permit.docx", "Unsupported Word permit format; no format profile matched")).isTrue();
        assertThat(WordFormatRequirement.isRequired(
                "permit.doc", "Không hỗ trợ định dạng file Word này vì chưa có format YAML phù hợp.")).isTrue();
        assertThat(WordFormatRequirement.isRequired(
                "permit.xlsx", "Unsupported Word permit format; no format profile matched")).isFalse();
        assertThat(WordFormatRequirement.isRequired(
                "permit.docx", "Không tìm thấy số phép bay trong file.")).isFalse();
    }
}
