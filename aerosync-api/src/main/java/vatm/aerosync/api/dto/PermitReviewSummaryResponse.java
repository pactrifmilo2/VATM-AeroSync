package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.PermitReviewStatus;

import java.time.LocalDateTime;

public record PermitReviewSummaryResponse(
        Long id,
        Long syncJobId,
        Long permitImportId,
        String normalizedPermitId,
        PermitReviewStatus status,
        String profileId,
        Integer profileVersion,
        Double confidence,
        String reviewReason,
        String correctedBy,
        LocalDateTime correctedAt,
        String approvedBy,
        LocalDateTime approvedAt,
        String publishRequestedBy,
        LocalDateTime publishRequestedAt,
        String publishedBy,
        LocalDateTime publishedAt,
        String publishError,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
