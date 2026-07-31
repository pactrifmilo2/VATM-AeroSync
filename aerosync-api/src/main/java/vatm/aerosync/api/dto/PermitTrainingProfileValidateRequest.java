package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PermitTrainingProfileValidateRequest(
        @NotNull @PositiveOrZero Long expectedVersion
) {
}
