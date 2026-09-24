package vatm.aerosync.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import vatm.aerosync.common.enums.EmailProcessingStatus;

import java.util.Set;

public record EmailResendRequest(
        @NotBlank String messageId,
        @NotEmpty Set<EmailProcessingStatus> statuses,
        Set<Long> attachmentIds
) {
    public EmailResendRequest {
        attachmentIds = attachmentIds == null ? Set.of() : Set.copyOf(attachmentIds);
    }

    public EmailResendRequest(String messageId, Set<EmailProcessingStatus> statuses) {
        this(messageId, statuses, Set.of());
    }
}
