package vatm.aerosync.api.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

public record EmailReportSummaryResponse(
        LocalDateTime from, LocalDateTime to, long totalRecords,
        Map<String, Long> processingStatusCounts,
        Map<String, Long> acknowledgementStatusCounts,
        long totalFiles, long successFiles, long errorFiles,
        long pendingFiles, long unknownFiles, OffsetDateTime asOf, String snapshotId
) {
    public EmailReportSummaryResponse(LocalDateTime from, LocalDateTime to, long totalRecords,
                                      Map<String, Long> processingStatusCounts,
                                      Map<String, Long> acknowledgementStatusCounts) {
        this(from, to, totalRecords, processingStatusCounts, acknowledgementStatusCounts,
                totalRecords, 0, 0, 0, totalRecords, OffsetDateTime.now(), null);
    }
}
