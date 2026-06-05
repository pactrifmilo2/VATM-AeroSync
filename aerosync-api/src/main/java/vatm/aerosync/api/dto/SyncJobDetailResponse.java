package vatm.aerosync.api.dto;

import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.enums.SyncStatus;

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
        String latestLogMessage
) {
}
