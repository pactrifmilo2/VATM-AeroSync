package vatm.aerosync.common.exception;

public class FormatValidationException extends RuntimeException {

    private final String fileName;
    private final String errorDetail;

    public FormatValidationException(String fileName, String errorDetail) {
        super("File format validation failed for %s: %s".formatted(fileName, errorDetail));
        this.fileName = fileName;
        this.errorDetail = errorDetail;
    }

    public String getFileName() {
        return fileName;
    }

    public String getErrorDetail() {
        return errorDetail;
    }
}
