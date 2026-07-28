package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

public record EmailReportDetailResponse(
        Long id,
        Long syncJobId,
        String permitNumber,
        String messageId,
        String mailboxFolder,
        Long uidValidity,
        Long messageUid,
        String sender,
        String subject,
        LocalDateTime receivedAt,
        int attachmentCount,
        Integer attachmentIndex,
        String attachmentName,
        String storedFileName,
        String errorMessage,
        String body,
        EmailProcessingStatus processingStatus,
        EmailAcknowledgementStatus acknowledgementStatus,
        boolean ingestComplete,
        LocalDateTime acknowledgedAt,
        String acknowledgementError,
        SyncStatus jobStatus
) {
}
