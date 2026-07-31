package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;

import java.time.LocalDateTime;
import java.util.List;

public record PermitTrainingGroupResponse(
        String profileId,
        int profileVersion,
        String semanticField,
        String aliasValue,
        String canonicalAlias,
        PermitTrainingStatus status,
        int evidenceCount,
        int minimumEvidence,
        int pendingCount,
        int approvedCount,
        int rejectedCount,
        int disabledCount,
        double averageConfidence,
        Long activeCandidateId,
        List<Long> candidateIds,
        List<Long> sourceReviewIds,
        long usageCount,
        LocalDateTime lastUsedAt,
        PermitTrainingValidationStatus validationStatus,
        LocalDateTime latestEvidenceAt
) {
}
