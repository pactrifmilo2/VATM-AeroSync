package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class PermitTrainingProfileLifecycleRequests {

    private PermitTrainingProfileLifecycleRequests() {
    }

    public record Activate(
            @NotNull @PositiveOrZero Long expectedVersion,
            boolean acknowledgement) {
    }

    public record Disable(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record Rollback(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull Long targetProfileId,
            @NotBlank @Size(max = 1000) String reason) {
    }
}
