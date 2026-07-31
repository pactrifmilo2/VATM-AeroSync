package vatm.aerosync.api.dto;

import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingSourceState;

import java.time.LocalDateTime;

public record PermitTrainingProfileEvidenceResponse(
        Long id,
        Long sourceId,
        Long fileRecordId,
        String originalFileName,
        PermitTrainingSourceState sourceState,
        boolean retained,
        PermitTrainingEvidenceKind kind,
        PermitTrainingEvidenceResult result,
        PermitReviewSnapshot expectedPermit,
        String actor,
        LocalDateTime evaluatedAt,
        String detail,
        LocalDateTime createdAt
) {
}
