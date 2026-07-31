package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vatm.aerosync.common.dto.PermitReviewSnapshot;

public record PermitCorrectionRequest(
        @NotNull PermitReviewSnapshot permit,
        @Size(max = 2000) String comment
) {
}
