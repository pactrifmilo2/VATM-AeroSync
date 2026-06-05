package vatm.aerosync.api.dto;

import java.util.Map;

public record DashboardStatsResponse(
        long totalJobs,
        Map<String, Long> statusCounts,
        long jobsLast24Hours,
        long failedLast24Hours,
        long activeAlerts
) {
}
