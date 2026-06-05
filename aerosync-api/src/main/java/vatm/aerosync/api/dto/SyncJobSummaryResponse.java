package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

public record SyncJobSummaryResponse(
        Long id,
        String fileHash,
        SyncStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
