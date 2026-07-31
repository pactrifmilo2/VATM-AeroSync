package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitTrainingStatus;

import java.time.LocalDateTime;

public record PermitTrainingCandidateResponse(
        Long id,
        Long sourceReviewId,
        PermitTrainingStatus status,
        String profileId,
        int profileVersion,
        String semanticField,
        String aliasValue,
        String matchMethod,
        double confidence,
        String proposedBy,
        String decisionComment,
        String decidedBy,
        LocalDateTime decidedAt,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
