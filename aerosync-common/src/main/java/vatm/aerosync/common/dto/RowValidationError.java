package vatm.aerosync.common.dto;

public record RowValidationError(
        int rowNumber,
        String field,
        String code,
        String message,
        String value
) {
}
