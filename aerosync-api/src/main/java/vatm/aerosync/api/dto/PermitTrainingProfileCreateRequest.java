package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PermitTrainingProfileCreateRequest(
        @NotBlank
        @Pattern(
                regexp = "^[a-z0-9][a-z0-9-]{2,119}$",
                message = "profileKey must use lowercase letters, numbers, and hyphens")
        String profileKey,
        @NotBlank @Size(max = 160) String displayName,
        @NotBlank
        @Pattern(
                regexp = "^[a-z0-9][a-z0-9-]{1,119}$",
                message = "family must use lowercase letters, numbers, and hyphens")
        String family,
        @Size(max = 120) String baseProfileId,
        @NotNull Long sourceId
) {
}
