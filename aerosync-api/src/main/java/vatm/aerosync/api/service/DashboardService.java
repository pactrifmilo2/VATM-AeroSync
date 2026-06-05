package vatm.aerosync.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.DashboardStatsResponse;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DashboardService {

    private final SyncJobRepository syncJobRepository;
    private final AlertService alertService;

    public DashboardService(SyncJobRepository syncJobRepository, AlertService alertService) {
        this.syncJobRepository = syncJobRepository;
        this.alertService = alertService;
    }

    @Transactional(readOnly = true)
    public DashboardStatsResponse getStats() {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        long total = 0;
        for (SyncStatus status : SyncStatus.values()) {
            long count = syncJobRepository.countByStatus(status);
            statusCounts.put(status.name(), count);
            total += count;
        }

        LocalDateTime since = LocalDateTime.now().minusHours(24);
        long jobsLast24Hours = syncJobRepository.findAll().stream()
                .filter(job -> job.getUpdatedAt() != null && !job.getUpdatedAt().isBefore(since))
                .count();
        long failedLast24Hours = syncJobRepository.findByStatus(SyncStatus.FAILED).stream()
                .filter(job -> job.getUpdatedAt() != null && !job.getUpdatedAt().isBefore(since))
                .count();

        return new DashboardStatsResponse(
                total,
                statusCounts,
                jobsLast24Hours,
                failedLast24Hours,
                alertService.countActive());
    }
}
