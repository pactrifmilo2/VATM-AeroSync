package vatm.aerosync.worker.service;

import vatm.aerosync.common.enums.PermitImportStatus;

public record PermitImportOutcome(
        PermitImportStatus status,
        int detailCount,
        Long targetMasterId,
        Long targetPermId
) {
}
