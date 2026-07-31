package vatm.aerosync.api.dto;

public record PermitFieldDiagnosticResponse(
        String field,
        double confidence,
        String source,
        String method
) {
}
