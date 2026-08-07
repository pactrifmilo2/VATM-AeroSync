package vatm.aerosync.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.entity.DashboardAlert;
import vatm.aerosync.api.repository.DashboardAlertRepository;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Clears the persisted processing state after a Gmail resend succeeds.
 *
 * Audit logs and dashboard notifications are deliberately retained. Their
 * foreign-key reference to the deleted job is detached first so the message
 * remains visible as a historical notification without blocking cleanup.
 */
@Service
public class EmailResendCleanupService {

    private static final Logger log = LoggerFactory.getLogger(EmailResendCleanupService.class);
    private static final String DEDUP_PREFIX = "aerosync:dedup:";

    private final EmailMetadataRepository emailMetadataRepository;
    private final FileRecordRepository fileRecordRepository;
    private final PermitImportRepository permitImportRepository;
    private final SyncJobRepository syncJobRepository;
    private final AuditLogRepository auditLogRepository;
    private final DashboardAlertRepository dashboardAlertRepository;
    private final AtfmTestResetService atfmTestResetService;
    private final StringRedisTemplate redisTemplate;

    public EmailResendCleanupService(EmailMetadataRepository emailMetadataRepository,
                                     FileRecordRepository fileRecordRepository,
                                     PermitImportRepository permitImportRepository,
                                     SyncJobRepository syncJobRepository,
                                     AuditLogRepository auditLogRepository,
                                     DashboardAlertRepository dashboardAlertRepository,
                                     AtfmTestResetService atfmTestResetService,
                                     StringRedisTemplate redisTemplate) {
        this.emailMetadataRepository = emailMetadataRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.permitImportRepository = permitImportRepository;
        this.syncJobRepository = syncJobRepository;
        this.auditLogRepository = auditLogRepository;
        this.dashboardAlertRepository = dashboardAlertRepository;
        this.atfmTestResetService = atfmTestResetService;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public CleanupResult cleanup(List<EmailMetadata> selectedMetadata, String messageId) {
        Set<Long> syncJobIds = selectedMetadata.stream()
                .map(EmailMetadata::getSyncJob)
                .filter(Objects::nonNull)
                .map(SyncJob::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int jobs = 0;
        int permits = 0;
        int details = 0;
        int files = 0;
        for (Long syncJobId : syncJobIds) {
            SyncJob job = syncJobRepository.findById(syncJobId)
                    .orElseThrow(() -> new IllegalStateException("Sync job not found during resend cleanup: " + syncJobId));
            List<EmailMetadata> jobMetadata = emailMetadataRepository.findBySyncJobId(syncJobId);
            boolean shared = jobMetadata.stream()
                    .anyMatch(metadata -> !Objects.equals(messageId, metadata.getMessageId()));
            if (shared) {
                throw new IllegalStateException(
                        "Cannot reset sync job " + syncJobId
                                + " because its SHA-256 is shared by another email");
            }

            PermitImport permitImport = permitImportRepository.findBySyncJobId(syncJobId).orElse(null);
            if (permitImport != null) {
                AtfmTestResetService.TargetDeleteResult deleted =
                        atfmTestResetService.deleteOwnedPermit(permitImport);
                permits += deleted.masterRows();
                details += deleted.detailRows();
                permitImportRepository.delete(permitImport);
            }

            // Keep the notification/audit history, but remove its FK to the
            // job that is about to be deleted.
            List<AuditLog> auditLogs = auditLogRepository.findBySyncJobIdOrderByTimestampDesc(syncJobId);
            auditLogs.forEach(logEntry -> logEntry.setSyncJob(null));
            auditLogRepository.saveAll(auditLogs);
            List<DashboardAlert> alerts = dashboardAlertRepository.findBySyncJobId(syncJobId);
            alerts.forEach(alert -> alert.setSyncJobId(null));
            dashboardAlertRepository.saveAll(alerts);

            List<FileRecord> records = fileRecordRepository.findBySyncJobId(syncJobId);
            for (FileRecord record : records) {
                deletePhysicalFile(record.getStoredPath());
            }
            files += records.size();
            fileRecordRepository.deleteAll(records);
            emailMetadataRepository.deleteAll(jobMetadata);

            String hash = job.getFileHash();
            syncJobRepository.delete(job);
            syncJobRepository.flush();
            if (hash != null && !hash.isBlank()) {
                Boolean removed = redisTemplate.delete(DEDUP_PREFIX + hash);
                log.info("Cleared resend deduplication key for sync job {}: {}", syncJobId, removed);
            }
            jobs++;
        }
        return new CleanupResult(jobs, permits, details, files);
    }

    private void deletePhysicalFile(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(storedPath));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot delete resend file " + storedPath, exception);
        }
    }

    public record CleanupResult(int syncJobsDeleted,
                                int permitsDeleted,
                                int detailsDeleted,
                                int filesDeleted) {
    }
}
