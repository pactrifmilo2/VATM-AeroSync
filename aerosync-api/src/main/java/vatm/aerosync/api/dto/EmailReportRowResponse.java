package vatm.aerosync.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import vatm.aerosync.api.service.DatabaseState;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

public record EmailReportRowResponse(
        Long id,
        Long syncJobId,
        String permitNumber,
        String messageId,
        String sender,
        String subject,
        LocalDateTime receivedAt,
        int attachmentCount,
        Integer attachmentIndex,
        String attachmentName,
        String storedFileName,
        String storage,
        String errorMessage,
        EmailProcessingStatus processingStatus,
        EmailAcknowledgementStatus acknowledgementStatus,
        boolean ingestComplete,
        LocalDateTime acknowledgedAt,
        SyncStatus jobStatus,
        boolean requiresFormat,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("source_key") int sourceKey,
        DatabaseState databaseState,
        boolean hasDatabaseData
) {
    public EmailReportRowResponse(
            Long id,
            Long syncJobId,
            String permitNumber,
            String messageId,
            String sender,
            String subject,
            LocalDateTime receivedAt,
            int attachmentCount,
            Integer attachmentIndex,
            String attachmentName,
            String storedFileName,
            String errorMessage,
            EmailProcessingStatus processingStatus,
            EmailAcknowledgementStatus acknowledgementStatus,
            boolean ingestComplete,
            LocalDateTime acknowledgedAt,
            SyncStatus jobStatus,
            boolean requiresFormat,
            String sourceType,
            int sourceKey,
            DatabaseState databaseState,
            boolean hasDatabaseData) {
        this(id, syncJobId, permitNumber, messageId, sender, subject, receivedAt,
                attachmentCount, attachmentIndex, attachmentName, storedFileName, null,
                errorMessage, processingStatus, acknowledgementStatus, ingestComplete,
                acknowledgedAt, jobStatus, requiresFormat, sourceType, sourceKey,
                databaseState, hasDatabaseData);
    }
}
