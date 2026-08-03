package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import vatm.aerosync.common.enums.EmailProcessingStatus;

import java.util.Set;

public record EmailResendRequest(
        @NotBlank String messageId,
        @NotEmpty Set<EmailProcessingStatus> statuses
) {
}
