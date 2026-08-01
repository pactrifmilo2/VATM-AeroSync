package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TestReplayRequest(
        @NotBlank(message = "confirmPermitId is required") String confirmPermitId) {
}
