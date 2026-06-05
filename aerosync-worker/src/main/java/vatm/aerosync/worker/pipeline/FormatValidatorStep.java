package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.worker.config.WorkerProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.support.FileTypeDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FormatValidatorStep {

    static final String[] REQUIRED_HEADERS = {"callsign", "from", "to", "dateflight"};

    private final WorkerProperties workerProperties;

    public FormatValidatorStep(WorkerProperties workerProperties) {
        this.workerProperties = workerProperties;
    }

    public void validate(ProcessingContext context) {
        Path file = context.getFilePath();
        String fileName = context.getOriginalFileName();

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new FormatValidationException(fileName, "File does not exist or is not a regular file");
        }

        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new FormatValidationException(fileName, "Cannot read file size");
        }

        if (size > workerProperties.getMaxFileSizeBytes()) {
            throw new FormatValidationException(fileName, "File exceeds maximum size");
        }

        if (size == 0) {
            throw new FormatValidationException(fileName, "File is empty");
        }

        try {
            context.setFileType(FileTypeDetector.detect(file));
        } catch (IllegalArgumentException e) {
            throw new FormatValidationException(fileName, e.getMessage());
        }

        if (context.getFileType() != vatm.aerosync.common.enums.FileType.XLSX) {
            validateUtf8(file, fileName);
        }

        if (context.getFileType() == vatm.aerosync.common.enums.FileType.CSV) {
            validateCsvHeader(file, fileName);
        }
    }

    private void validateUtf8(Path file, String fileName) {
        try {
            Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException e) {
            throw new FormatValidationException(fileName, "File is not valid UTF-8");
        } catch (IOException e) {
            throw new FormatValidationException(fileName, "Cannot read file encoding");
        }
    }

    private void validateCsvHeader(Path file, String fileName) {
        try {
            String firstLine = Files.lines(file, StandardCharsets.UTF_8).findFirst()
                    .orElseThrow(() -> new FormatValidationException(fileName, "CSV has no header row"));
            String[] headers = firstLine.toLowerCase().split(",");
            for (String required : REQUIRED_HEADERS) {
                boolean found = false;
                for (String header : headers) {
                    if (header.trim().equals(required)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new FormatValidationException(fileName, "Missing required column: " + required);
                }
            }
        } catch (FormatValidationException e) {
            throw e;
        } catch (IOException e) {
            throw new FormatValidationException(fileName, "Cannot read CSV header");
        }
    }
}
