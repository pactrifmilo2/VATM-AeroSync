package vatm.aerosync.worker.model;

public record PermitFieldDiagnostic(
        String field,
        double confidence,
        String source,
        String method,
        String observedValue
) {
    public PermitFieldDiagnostic(String field,
                                 double confidence,
                                 String source,
                                 String method) {
        this(field, confidence, source, method, null);
    }
}
