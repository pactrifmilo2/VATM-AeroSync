package vatm.aerosync.api.dto;

import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.enums.PermitTrainingSourceState;

import java.time.LocalDateTime;

public record PermitTrainingSourceDetailResponse(
        Long id,
        Long fileRecordId,
        Long syncJobId,
        PermitTrainingSourceState state,
        String sourceHash,
        String originalFileName,
        String profileId,
        Integer profileVersion,
        Double confidence,
        PermitTrainingDocument document,
        String parseError,
        boolean retained,
        LocalDateTime retainedAt,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
