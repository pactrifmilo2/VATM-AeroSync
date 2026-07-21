package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.common.enums.FileType;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.config.WorkerProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormatValidatorStepTest {

    @TempDir
    Path tempDir;

    private FormatValidatorStep formatValidatorStep;

    @BeforeEach
    void setUp() {
        WorkerProperties properties = new WorkerProperties();
        properties.setMaxFileSizeBytes(1024);
        formatValidatorStep = new FormatValidatorStep(properties);
    }

    @Test
    void validate_acceptsValidCsvWithRequiredHeaders() throws Exception {
        Path file = tempDir.resolve("flight.csv");
        Files.writeString(file, "callsign,from,to,dateflight\nVN123,HAN,SGN,2026-06-01");
        ProcessingContext context = contextFor(file);

        formatValidatorStep.validate(context);

        assertThat(context.getFileType()).isEqualTo(FileType.CSV);
    }

    @Test
    void validate_rejectsUnsupportedExtension() throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "hello");
        ProcessingContext context = contextFor(file);

        assertThatThrownBy(() -> formatValidatorStep.validate(context))
                .isInstanceOf(FormatValidationException.class)
                .hasMessageContaining("Unsupported extension");
    }

    @Test
    void validate_acceptsBinaryDocxWithoutUtf8Check() throws Exception {
        Path file = tempDir.resolve("permit.docx");
        Files.write(file, new byte[] {'P', 'K', 3, 4, (byte) 0xFF});
        ProcessingContext context = contextFor(file);

        formatValidatorStep.validate(context);

        assertThat(context.getFileType()).isEqualTo(FileType.DOCX);
    }

    @Test
    void validate_rejectsCsvMissingRequiredColumn() throws Exception {
        Path file = tempDir.resolve("bad.csv");
        Files.writeString(file, "callsign,from,to\nVN123,HAN,SGN");
        ProcessingContext context = contextFor(file);

        assertThatThrownBy(() -> formatValidatorStep.validate(context))
                .isInstanceOf(FormatValidationException.class)
                .hasMessageContaining("dateflight");
    }

    @Test
    void validate_rejectsNonUtf8Content() throws Exception {
        Path file = tempDir.resolve("bad.csv");
        Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xFE, 'a'});
        ProcessingContext context = contextFor(file);

        assertThatThrownBy(() -> formatValidatorStep.validate(context))
                .isInstanceOf(FormatValidationException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void validate_rejectsOversizedFile() throws Exception {
        WorkerProperties properties = new WorkerProperties();
        properties.setMaxFileSizeBytes(10);
        FormatValidatorStep strict = new FormatValidatorStep(properties);
        Path file = tempDir.resolve("big.csv");
        Files.writeString(file, "callsign,from,to,dateflight\n" + "x".repeat(50));
        ProcessingContext context = contextFor(file);

        assertThatThrownBy(() -> strict.validate(context))
                .isInstanceOf(FormatValidationException.class)
                .hasMessageContaining("maximum size");
    }

    private ProcessingContext contextFor(Path file) {
        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(1L, file.toString(), "hash", FileSourceType.FILESYSTEM, false));
        context.setFilePath(file);
        return context;
    }
}
