package vatm.aerosync.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vatm.aerosync.api.config.TestReplayProperties;
import vatm.aerosync.api.dto.TestReplayResponse;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.NoSuchElementException;

@Service
public class TestReplayService {

    private final TestReplayProperties properties;
    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final EmailMetadataRepository emailMetadataRepository;
    private final PermitImportRepository permitImportRepository;
    private final AuditLogRepository auditLogRepository;
    private final AtfmTestResetService atfmTestResetService;
    private final JobRetryPublisher jobRetryPublisher;

    public TestReplayService(TestReplayProperties properties,
                             SyncJobRepository syncJobRepository,
                             FileRecordRepository fileRecordRepository,
                             EmailMetadataRepository emailMetadataRepository,
                             PermitImportRepository permitImportRepository,
                             AuditLogRepository auditLogRepository,
                             AtfmTestResetService atfmTestResetService,
                             JobRetryPublisher jobRetryPublisher) {
        this.properties = properties;
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.emailMetadataRepository = emailMetadataRepository;
        this.permitImportRepository = permitImportRepository;
        this.auditLogRepository = auditLogRepository;
        this.atfmTestResetService = atfmTestResetService;
        this.jobRetryPublisher = jobRetryPublisher;
    }

    @Transactional
    public TestReplayResponse replay(Long jobId, String confirmedPermitId) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Test replay is disabled; set APP_TEST_REPLAY_ENABLED=true only in a test environment");
        }

        SyncJob job = syncJobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Sync job not found: " + jobId));
        if (job.getStatus() == SyncStatus.PENDING || job.getStatus() == SyncStatus.IN_PROGRESS) {
            throw new IllegalStateException("Job " + jobId + " is still active and cannot be replayed");
        }

        FileRecord latest = fileRecordRepository.findBySyncJobId(jobId).stream()
                .max(Comparator.comparing(FileRecord::getCreatedAt))
                .orElseThrow(() -> new IllegalStateException("No file records for job: " + jobId));
        if (latest.getSourceType() != FileSourceType.EMAIL) {
            throw new IllegalArgumentException("Test replay only supports email-ingested jobs");
        }
        if (!Files.isRegularFile(Path.of(latest.getStoredPath()))) {
            throw new IllegalStateException("Archived attachment is no longer available: " + latest.getStoredPath());
        }

        PermitImport permitImport = permitImportRepository.findBySyncJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job " + jobId + " is not a permit import"));
        if (!permitImport.getNormalizedPermitId().equals(confirmedPermitId)) {
            throw new IllegalArgumentException(
                    "confirmPermitId must exactly match " + permitImport.getNormalizedPermitId());
        }
        if (permitImport.getStatus() == PermitImportStatus.DUPLICATE) {
            throw new IllegalStateException(
                    "This job did not create the target permit; replay the original SAVED job instead");
        }
        if (permitImport.getStatus() == PermitImportStatus.SAVED && !properties.isAtfmWriteEnabled()) {
            throw new IllegalStateException(
                    "APP_ATFM_WRITE_ENABLED must be true before deleting and replaying a saved target permit");
        }

        AtfmTestResetService.TargetDeleteResult deleted =
                atfmTestResetService.deleteOwnedPermit(permitImport);
        recordReplayAudit(job, permitImport, deleted);
        permitImport.setStatus(PermitImportStatus.RESERVED);
        permitImport.setTargetMasterId(null);
        permitImport.setTargetPermId(null);
        permitImport.setDetailCount(null);
        permitImport.setErrorMessage(null);
        permitImportRepository.saveAndFlush(permitImport);

        job.setStatus(SyncStatus.PENDING);
        syncJobRepository.save(job);
        latest.setProcessingStatus(FileProcessingStatus.DOWNLOADED);
        latest.setRowsSaved(null);
        latest.setDatabaseSavedAt(null);
        latest.setArchiveStatus(FileArchiveStatus.PENDING);
        latest.setArchivedAt(null);
        latest.setErrorMessage(null);
        fileRecordRepository.save(latest);

        EmailMetadata metadata = emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(jobId).orElse(null);
        if (metadata != null) {
            metadata.setProcessingStatus(EmailProcessingStatus.DOWNLOADED);
            emailMetadataRepository.save(metadata);
        }

        boolean priority = (latest.getOriginalFileName() != null
                && latest.getOriginalFileName().toUpperCase(java.util.Locale.ROOT).contains("VIP"))
                || (metadata != null && metadata.getSubject() != null
                && metadata.getSubject().toUpperCase(java.util.Locale.ROOT).contains("VIP"));
        FileIngestedEvent event = new FileIngestedEvent(
                jobId,
                latest.getStoredPath(),
                job.getFileHash(),
                FileSourceType.EMAIL,
                priority);
        publishAfterCommit(event);

        return new TestReplayResponse(
                jobId,
                permitImport.getNormalizedPermitId(),
                deleted.masterRows(),
                deleted.detailRows(),
                true);
    }

    private void publishAfterCommit(FileIngestedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            jobRetryPublisher.publish(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobRetryPublisher.publish(event);
            }
        });
    }

    private void recordReplayAudit(SyncJob job,
                                   PermitImport permitImport,
                                   AtfmTestResetService.TargetDeleteResult deleted) {
        AuditLog auditLog = new AuditLog();
        auditLog.setSyncJob(job);
        auditLog.setAction("TEST_REPLAY_QUEUED");
        auditLog.setInputSummary("permit=" + permitImport.getNormalizedPermitId()
                + ",targetMasterId=" + permitImport.getTargetMasterId()
                + ",targetPermId=" + permitImport.getTargetPermId());
        auditLog.setOutputSummary("deletedTargetMasters=" + deleted.masterRows()
                + ",deletedTargetDetails=" + deleted.detailRows());
        auditLog.setResultStatus(SyncStatus.PENDING);
        auditLogRepository.save(auditLog);
    }
}
