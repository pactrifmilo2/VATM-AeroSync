package vatm.aerosync.api.dto;

import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.enums.PermitReviewStatus;

import java.time.LocalDateTime;
import java.util.List;

public record PermitReviewDetailResponse(
        Long id,
        Long syncJobId,
        Long permitImportId,
        String normalizedPermitId,
        PermitReviewStatus status,
        String profileId,
        Integer profileVersion,
        Double confidence,
        Double runnerUpMargin,
        String reviewReason,
        PermitReviewSnapshot originalPermit,
        PermitReviewSnapshot correctedPermit,
        PermitReviewSnapshot publishedPermit,
        List<PermitProfileCandidateResponse> candidates,
        List<PermitFieldDiagnosticResponse> fields,
        List<PermitParseWarningResponse> warnings,
        String correctionComment,
        String correctedBy,
        LocalDateTime correctedAt,
        String approvalComment,
        String approvedBy,
        LocalDateTime approvedAt,
        String rejectionReason,
        String rejectedBy,
        LocalDateTime rejectedAt,
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
