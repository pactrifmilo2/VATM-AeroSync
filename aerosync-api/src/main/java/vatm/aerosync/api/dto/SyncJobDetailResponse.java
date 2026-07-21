package vatm.aerosync.api.dto;

import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.PermitImportStatus;

import java.time.LocalDateTime;
import java.util.List;

public record SyncJobDetailResponse(
        Long id,
        String fileHash,
        SyncStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<FileRecordResponse> fileRecords,
        List<RowValidationError> rowErrors,
        String latestLogMessage,
        String emailSubject,
        String emailBody,
        EmailProcessingStatus emailProcessingStatus,
        EmailAcknowledgementStatus emailAcknowledgementStatus,
        LocalDateTime emailAcknowledgedAt,
        String emailAcknowledgementError,
        String mailboxFolder,
        Long messageUid,
        PermitImportStatus permitImportStatus,
        String normalizedPermitId,
        Long targetMasterId,
        Long targetPermId,
        Integer permitDetailCount,
        String permitImportError
) {
    public SyncJobDetailResponse(Long id,
                                 String fileHash,
                                 SyncStatus status,
                                 LocalDateTime createdAt,
                                 LocalDateTime updatedAt,
                                 List<FileRecordResponse> fileRecords,
                                 List<RowValidationError> rowErrors,
                                 String latestLogMessage,
                                 String emailSubject,
                                 String emailBody) {
        this(
                id,
                fileHash,
                status,
                createdAt,
                updatedAt,
                fileRecords,
                rowErrors,
                latestLogMessage,
                emailSubject,
                emailBody,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
