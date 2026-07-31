package vatm.aerosync.api.dto;

public record PermitParseWarningResponse(
        String code,
        String message,
        boolean reviewRequired
) {
}
