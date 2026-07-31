package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;

import java.time.LocalDateTime;

public record PermitTrainingCandidateResponse(
        Long id,
        Long sourceReviewId,
        PermitTrainingStatus status,
        String profileId,
        int profileVersion,
        String semanticField,
        String aliasValue,
        String canonicalAlias,
        String matchMethod,
        double confidence,
        int evidenceCount,
        int minimumEvidence,
        String proposedBy,
        String decisionComment,
        String decidedBy,
        LocalDateTime decidedAt,
        long usageCount,
        LocalDateTime lastUsedAt,
        PermitTrainingValidationStatus validationStatus,
        String validationRequestedBy,
        LocalDateTime validationRequestedAt,
        LocalDateTime validationCompletedAt,
        Integer validationCorpusSize,
        Integer validationPassedCount,
        Integer validationFailedCount,
        String validationReport,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
