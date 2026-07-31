package vatm.aerosync.common.dto;

import java.time.LocalDateTime;

public record PermitReviewPublishCommand(
        Long reviewId,
        String requestedBy,
        LocalDateTime requestedAt
) {
}
