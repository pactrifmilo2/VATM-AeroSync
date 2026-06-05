package vatm.aerosync.api.dto;

import vatm.aerosync.common.enums.FileSourceType;

import java.time.LocalDateTime;

public record FileRecordResponse(
        Long id,
        FileSourceType sourceType,
        String originalFileName,
        String storedPath,
        LocalDateTime createdAt
) {
}
