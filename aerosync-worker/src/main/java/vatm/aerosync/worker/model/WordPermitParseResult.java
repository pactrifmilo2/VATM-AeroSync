package vatm.aerosync.worker.model;

import java.util.List;

public record WordPermitParseResult(
        SchedulePermit permit,
        String profileId,
        int profileVersion,
        double confidence,
        double runnerUpMargin,
        boolean reviewRequired,
        List<PermitProfileCandidate> candidates,
        List<PermitFieldDiagnostic> fields,
        List<PermitParseWarning> warnings
) {
    public WordPermitParseResult {
        candidates = List.copyOf(candidates);
        fields = List.copyOf(fields);
        warnings = List.copyOf(warnings);
    }
}
