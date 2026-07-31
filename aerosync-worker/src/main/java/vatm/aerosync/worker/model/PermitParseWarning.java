package vatm.aerosync.worker.model;

public record PermitParseWarning(
        String code,
        String message,
        boolean reviewRequired
) {
}
