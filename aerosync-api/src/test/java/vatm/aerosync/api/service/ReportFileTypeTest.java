package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReportFileTypeTest {
    @Test
    void matchesExtensionsCaseInsensitivelyAndUsesStoredNameFirst() {
        assertThat(ReportFileType.WORD.matches("stored.DOCX", "fallback.pdf")).isTrue();
        assertThat(ReportFileType.PDF.matches("stored.DOCX", "fallback.pdf")).isFalse();
        assertThat(ReportFileType.EXCEL.matches(null, "sheet.XLSM")).isTrue();
        assertThat(ReportFileType.IMAGE.matches(null, "photo.JPEG")).isTrue();
    }

    @Test
    void noneOnlyMatchesMissingNamesAndUnknownExtensionMatchesNoGroup() {
        assertThat(ReportFileType.NONE.matches("", null)).isTrue();
        assertThat(ReportFileType.NONE.matches(null, "file.bin")).isFalse();
        assertThat(ReportFileType.WORD.matches(null, "file.bin")).isFalse();
    }
}