package vatm.aerosync.api.service;

import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;

import java.time.LocalDateTime;

public record EmailReportFilter(
        LocalDateTime from,
        LocalDateTime to,
        EmailProcessingStatus processingStatus,
        EmailAcknowledgementStatus acknowledgementStatus,
        SyncStatus jobStatus,
        String sender,
        String query
) {
}
