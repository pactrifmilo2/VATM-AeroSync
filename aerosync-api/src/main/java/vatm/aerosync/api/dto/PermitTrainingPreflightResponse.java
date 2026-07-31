package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitTrainingValidationStatus;

import java.util.List;

public record PermitTrainingPreflightResponse(
        Long candidateId,
        boolean ready,
        int evidenceCount,
        int minimumEvidence,
        boolean evidenceReady,
        boolean validationRequired,
        PermitTrainingValidationStatus validationStatus,
        boolean validationReady,
        Long activeCandidateId,
        List<Long> evidenceCandidateIds,
        List<Long> sourceReviewIds,
        List<Long> conflictingCandidateIds,
        List<String> blockers
) {
}
