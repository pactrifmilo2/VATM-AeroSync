package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;

public record PermitTrainingProfileUpdateRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull PermitTrainingProfileDefinition definition
) {
}
