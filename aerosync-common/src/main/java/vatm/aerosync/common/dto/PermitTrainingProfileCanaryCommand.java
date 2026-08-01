package vatm.aerosync.common.dto;

import java.time.LocalDateTime;

public record PermitTrainingProfileCanaryCommand(
        Long profileId,
        Long evidenceId,
        String definitionChecksum,
        String requestedBy,
        LocalDateTime requestedAt
) {
}
