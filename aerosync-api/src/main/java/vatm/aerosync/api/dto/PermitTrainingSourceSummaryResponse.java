package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitTrainingSourceState;

import java.time.LocalDateTime;

public record PermitTrainingSourceSummaryResponse(
        Long id,
        Long fileRecordId,
        Long syncJobId,
        PermitTrainingSourceState state,
        String sourceHash,
        String originalFileName,
        String profileId,
        Integer profileVersion,
        Double confidence,
        boolean retained,
        LocalDateTime retainedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
