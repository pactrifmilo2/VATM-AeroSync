package vatm.aerosync.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.time.LocalDateTime;
import java.util.Comparator;

/** Republishes jobs whose state commit succeeded but whose broker publish was lost. */
@Service
public class StaleSyncJobRecoveryService {
    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final JobRetryPublisher publisher;
    private final long staleMinutes;

    public StaleSyncJobRecoveryService(SyncJobRepository syncJobRepository,
                                       FileRecordRepository fileRecordRepository,
                                       JobRetryPublisher publisher,
                                       @Value("${aerosync.retry-recovery-stale-minutes:15}") long staleMinutes) {
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.publisher = publisher;
        this.staleMinutes = staleMinutes;
    }

    @Scheduled(fixedDelayString = "${aerosync.retry-recovery-delay-ms:300000}")
    public void recoverScheduled() {
        recoverStalePendingJobs(LocalDateTime.now().minusMinutes(staleMinutes));
    }

    @Transactional(readOnly = true)
    public int recoverStalePendingJobs(LocalDateTime cutoff) {
        int published = 0;
        for (SyncJob job : syncJobRepository.findByStatusAndUpdatedAtBefore(SyncStatus.PENDING, cutoff)) {
            FileRecord record = fileRecordRepository.findBySyncJobId(job.getId()).stream()
                    .max(Comparator.comparing(FileRecord::getCreatedAt)).orElse(null);
            if (record == null || record.getStoredPath() == null) continue;
            boolean priority = record.getSourceType() == FileSourceType.EMAIL
                    && record.getOriginalFileName() != null
                    && record.getOriginalFileName().toUpperCase(java.util.Locale.ROOT).contains("VIP");
            publisher.publish(new FileIngestedEvent(job.getId(), record.getStoredPath(), job.getFileHash(),
                    record.getSourceType(), priority));
            published++;
        }
        return published;
    }
}
