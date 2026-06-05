package vatm.aerosync.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionTest {

    @Test
    void formatValidationExceptionCarriesFileNameAndErrorDetail() {
        FormatValidationException exception = new FormatValidationException("flight.csv", "Unsupported encoding");

        assertThat(exception.getFileName()).isEqualTo("flight.csv");
        assertThat(exception.getErrorDetail()).isEqualTo("Unsupported encoding");
        assertThat(exception).hasMessageContaining("flight.csv")
                .hasMessageContaining("Unsupported encoding");
    }

    @Test
    void businessRuleExceptionCarriesRuleCodeAndMessage() {
        BusinessRuleException exception = new BusinessRuleException("BR-02", "Batch must be atomic");

        assertThat(exception.getRuleCode()).isEqualTo("BR-02");
        assertThat(exception).hasMessageContaining("BR-02")
                .hasMessageContaining("Batch must be atomic");
    }

    @Test
    void duplicateFileExceptionCarriesFileHash() {
        String hash = "e3b0c44298fc1c149afbf4c8996fb924";

        DuplicateFileException exception = new DuplicateFileException(hash);

        assertThat(exception.getFileHash()).isEqualTo(hash);
        assertThat(exception).hasMessageContaining(hash);
    }
}
