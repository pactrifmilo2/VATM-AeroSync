package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

public record SyncJobSummaryResponse(
        Long id,
        String fileHash,
        String originalFileName,
        SyncStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String sender,
        LocalDateTime emailReceivedAt,
        String storedPath
) {
}
