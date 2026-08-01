package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitTrainingProfileStatus;

import java.util.List;

public record PermitTrainingProfileCanaryReadinessResponse(
        Long profileId,
        PermitTrainingProfileStatus status,
        int minimumSuccesses,
        int passedCount,
        int failedCount,
        int pendingCount,
        boolean readyForActivationReview,
        List<String> blockers
) {
    public PermitTrainingProfileCanaryReadinessResponse {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
