package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PermitTrainingProfileConfirmRequest(
        @NotNull @PositiveOrZero Long expectedVersion
) {
}
