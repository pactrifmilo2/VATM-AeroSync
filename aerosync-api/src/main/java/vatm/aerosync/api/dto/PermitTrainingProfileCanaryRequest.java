package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vatm.aerosync.common.dto.PermitReviewSnapshot;

public record PermitTrainingProfileCanaryRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull Long sourceId,
        @NotNull PermitReviewSnapshot expectedPermit
) {
}
