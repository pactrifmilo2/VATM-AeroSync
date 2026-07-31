package vatm.aerosync.worker.pipeline;

import vatm.aerosync.worker.model.PermitParseWarning;
import vatm.aerosync.worker.model.PermitProfileCandidate;

import java.util.List;

record WordPermitDetectionResult(
        DocxPermitFormatProfile profile,
        DocxPermitFormatProfile declaredProfile,
        double confidence,
        double runnerUpMargin,
        boolean reviewRequired,
        List<PermitProfileCandidate> candidates,
        List<PermitParseWarning> warnings,
        PermitSemanticEvidence semantics
) {
    WordPermitDetectionResult {
        candidates = List.copyOf(candidates);
        warnings = List.copyOf(warnings);
    }
}
