package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

public record EmailReportRowResponse(
        Long id,
        Long syncJobId,
        String messageId,
        String sender,
        String subject,
        LocalDateTime receivedAt,
        int attachmentCount,
        Integer attachmentIndex,
        String attachmentName,
        EmailProcessingStatus processingStatus,
        EmailAcknowledgementStatus acknowledgementStatus,
        boolean ingestComplete,
        LocalDateTime acknowledgedAt,
        SyncStatus jobStatus
) {
}
