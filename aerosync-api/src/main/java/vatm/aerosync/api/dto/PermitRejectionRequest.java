package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PermitRejectionRequest(
        @NotBlank @Size(max = 2000) String reason
) {
}
