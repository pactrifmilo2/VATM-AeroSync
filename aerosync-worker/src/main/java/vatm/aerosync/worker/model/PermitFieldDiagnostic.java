package vatm.aerosync.worker.model;

public record PermitFieldDiagnostic(
        String field,
        double confidence,
        String source,
        String method
) {
}
