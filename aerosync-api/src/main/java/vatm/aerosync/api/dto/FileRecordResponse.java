package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.FileSourceType;

import java.time.LocalDateTime;

public record FileRecordResponse(
        Long id,
        FileSourceType sourceType,
        String originalFileName,
        String storedFileName,
        String storedPath,
        FileProcessingStatus processingStatus,
        Integer rowsSaved,
        LocalDateTime downloadedAt,
        LocalDateTime databaseSavedAt,
        FileArchiveStatus archiveStatus,
        LocalDateTime archivedAt,
        String errorMessage,
        Long fileSize,
        String checksum,
        LocalDateTime createdAt,
        String sender,
        String subject
) {
}
