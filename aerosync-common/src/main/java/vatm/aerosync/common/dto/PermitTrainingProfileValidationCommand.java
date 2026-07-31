package vatm.aerosync.common.dto;

import java.time.LocalDateTime;

public record PermitTrainingProfileValidationCommand(
        Long profileId,
        String definitionChecksum,
        String requestedBy,
        LocalDateTime requestedAt
) {
}
