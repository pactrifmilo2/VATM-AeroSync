package vatm.aerosync.api.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record EmailReportSummaryResponse(
        LocalDateTime from,
        LocalDateTime to,
        long totalRecords,
        Map<String, Long> processingStatusCounts,
        Map<String, Long> acknowledgementStatusCounts
) {
}
