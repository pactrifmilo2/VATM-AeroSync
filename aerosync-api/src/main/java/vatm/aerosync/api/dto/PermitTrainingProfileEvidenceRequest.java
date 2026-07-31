package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;

public record PermitTrainingProfileEvidenceRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull Long sourceId,
        PermitTrainingEvidenceKind kind,
        @NotNull PermitReviewSnapshot expectedPermit
) {
}
