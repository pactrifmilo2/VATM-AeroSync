package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

public record AuditLogResponse(
        Long id,
        Long syncJobId,
        String action,
        SyncStatus resultStatus,
        LocalDateTime timestamp,
        Long durationMs,
        FileSourceType sourceType,
        String message
) {
    public AuditLogResponse(Long id,
                            Long syncJobId,
                            String action,
                            SyncStatus resultStatus,
                            LocalDateTime timestamp,
                            Long durationMs,
                            FileSourceType sourceType) {
        this(id, syncJobId, action, resultStatus, timestamp, durationMs, sourceType, null);
    }
}
