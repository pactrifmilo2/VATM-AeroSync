package vatm.aerosync.common.dto;

import java.time.LocalDateTime;

public record PermitTrainingValidationCommand(
        Long candidateId,
        String requestedBy,
        LocalDateTime requestedAt
) {
}
