package vatm.aerosync.api.dto;

import jakarta.validation.constraints.Size;

public record PermitTrainingDecisionRequest(
        @Size(max = 2000, message = "comment must not exceed 2000 characters")
        String comment
) {
}
