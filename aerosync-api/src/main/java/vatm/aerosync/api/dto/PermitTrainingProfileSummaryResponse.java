package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitTrainingProfileStatus;

import java.time.LocalDateTime;

public record PermitTrainingProfileSummaryResponse(
        Long id,
        String profileKey,
        int profileVersion,
        PermitTrainingProfileStatus status,
        String displayName,
        String family,
        String baseProfileId,
        Integer baseProfileVersion,
        int evidenceCount,
        int canarySuccessCount,
        String createdBy,
        String confirmedBy,
        LocalDateTime confirmedAt,
        String lastError,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
