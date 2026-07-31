package vatm.aerosync.api.dto;

import java.time.LocalDateTime;

public record PermitTrainingProfileEventResponse(
        Long id,
        String action,
        String actor,
        String detail,
        LocalDateTime createdAt
) {
}
