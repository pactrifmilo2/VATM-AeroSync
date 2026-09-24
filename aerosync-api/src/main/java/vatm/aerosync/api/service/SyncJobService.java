package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vatm.aerosync.api.dto.FileRecordResponse;
import vatm.aerosync.api.dto.SyncJobDetailResponse;
import vatm.aerosync.api.dto.SyncJobSummaryResponse;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class SyncJobService {

    private static final int ORACLE_IN_QUERY_BATCH_SIZE = 900;
    static final int MAX_LIST_LIMIT = 50;

    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final EmailMetadataRepository emailMetadataRepository;
    private final JobRetryPublisher jobRetryPublisher;
    private final AuditLogRepository auditLogRepository;
    private final PermitImportRepository permitImportRepository;
    private final VietnameseErrorMessageTranslator errorMessageTranslator;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public SyncJobService(SyncJobRepository syncJobRepository,
                          FileRecordRepository fileRecordRepository,
                          EmailMetadataRepository emailMetadataRepository,
                          JobRetryPublisher jobRetryPublisher,
                          AuditLogRepository auditLogRepository,
                          PermitImportRepository permitImportRepository,
                          VietnameseErrorMessageTranslator errorMessageTranslator) {
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.emailMetadataRepository = emailMetadataRepository;
        this.jobRetryPublisher = jobRetryPublisher;
        this.auditLogRepository = auditLogRepository;
        this.permitImportRepository = permitImportRepository;
        this.errorMessageTranslator = errorMessageTranslator;
    }

    @Transactional(readOnly = true)
    public List<SyncJobSummaryResponse> listJobs(SyncStatus statusFilter) {
        List<SyncJob> jobs = statusFilter == null
                ? syncJobRepository.findAll()
                : syncJobRepository.findByStatus(statusFilter);
        List<Long> jobIds = jobs.stream().map(SyncJob::getId).toList();
        Map<Long, FileRecord> latestRecords = findLatestRecords(jobIds);
        List<Long> emailJobIds = latestRecords.entrySet().stream()
                .filter(entry -> entry.getValue().getSourceType() == FileSourceType.EMAIL)
                .map(Map.Entry::getKey)
                .toList();
        Map<Long, EmailMetadata> firstEmailMetadata = findFirstEmailMetadata(emailJobIds);

        return jobs.stream()
                .sorted(Comparator.comparing(SyncJob::getCreatedAt).reversed())
                .map(job -> toSummary(
                        job,
                        latestRecords.get(job.getId()),
                        firstEmailMetadata.get(job.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SyncJobSummaryResponse> listJobs(SyncStatus statusFilter, int limit) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException("'limit' must be between 1 and " + MAX_LIST_LIMIT);
        }

        var pageRequest = PageRequest.of(
                0,
                limit,
                Sort.by(Sort.Order.desc("createdAt")));
        List<SyncJob> jobs = statusFilter == null
                ? syncJobRepository.findAll(pageRequest).getContent()
                : syncJobRepository.findByStatus(statusFilter, pageRequest).getContent();
        return summarizeJobs(jobs);
    }

    private List<SyncJobSummaryResponse> summarizeJobs(List<SyncJob> jobs) {
        List<Long> jobIds = jobs.stream().map(SyncJob::getId).toList();
        Map<Long, FileRecord> latestRecords = findLatestRecords(jobIds);
        List<Long> emailJobIds = latestRecords.entrySet().stream()
                .filter(entry -> entry.getValue().getSourceType() == FileSourceType.EMAIL)
                .map(Map.Entry::getKey)
                .toList();
        Map<Long, EmailMetadata> firstEmailMetadata = findFirstEmailMetadata(emailJobIds);

        return jobs.stream()
                .map(job -> toSummary(
                        job,
                        latestRecords.get(job.getId()),
                        firstEmailMetadata.get(job.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SyncJobDetailResponse getJob(Long id) {
        SyncJob job = syncJobRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Sync job not found: " + id));
        List<FileRecordResponse> records = fileRecordRepository.findBySyncJobId(id).stream()
                .map(this::toFileRecordResponse)
                .toList();
        JobDetailLog latestLog = latestDetailLog(id);

        String emailSubject = null;
        String emailBody = null;
        vatm.aerosync.common.enums.EmailProcessingStatus emailProcessingStatus = null;
        vatm.aerosync.common.enums.EmailAcknowledgementStatus emailAcknowledgementStatus = null;
        LocalDateTime emailAcknowledgedAt = null;
        String emailAcknowledgementError = null;
        String mailboxFolder = null;
        Long messageUid = null;
        var emailMetadata = emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(id);
        if (emailMetadata != null && emailMetadata.isPresent()) {
            emailSubject = emailMetadata.get().getSubject();
            emailBody = emailMetadata.get().getBody();
            emailProcessingStatus = emailMetadata.get().getProcessingStatus();
            emailAcknowledgementStatus = emailMetadata.get().getAcknowledgementStatus();
            emailAcknowledgedAt = emailMetadata.get().getAcknowledgedAt();
            emailAcknowledgementError = errorMessageTranslator.translate(
                    emailMetadata.get().getAcknowledgementError());
            mailboxFolder = emailMetadata.get().getMailboxFolder();
            messageUid = emailMetadata.get().getMessageUid();
        }

        var permitImport = permitImportRepository.findBySyncJobId(id).orElse(null);

        return new SyncJobDetailResponse(
                job.getId(),
                job.getFileHash(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                records,
                translateRowErrors(latestLog.rowErrors()),
                errorMessageTranslator.translate(latestLog.message()),
                emailSubject,
                emailBody,
                emailProcessingStatus,
                emailAcknowledgementStatus,
                emailAcknowledgedAt,
                emailAcknowledgementError,
                mailboxFolder,
                messageUid,
                permitImport != null ? permitImport.getStatus() : null,
                permitImport != null ? permitImport.getNormalizedPermitId() : null,
                permitImport != null ? permitImport.getTargetMasterId() : null,
                permitImport != null ? permitImport.getTargetPermId() : null,
                permitImport != null ? permitImport.getDetailCount() : null,
                permitImport != null
                        ? errorMessageTranslator.translate(permitImport.getErrorMessage())
                        : null);
    }

    private List<RowValidationError> translateRowErrors(List<RowValidationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        return errors.stream()
                .map(error -> new RowValidationError(
                        error.rowNumber(),
                        error.field(),
                        error.code(),
                        errorMessageTranslator.translate(error.message()),
                        error.value()))
                .toList();
    }

    @Transactional
    public void retryJob(Long id) {
        SyncJob job = syncJobRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Sync job not found: " + id));
        if (!EnumSet.of(SyncStatus.FAILED, SyncStatus.SKIPPED, SyncStatus.QUARANTINED)
                .contains(job.getStatus())) {
            throw new IllegalStateException(
                    "Only FAILED, SKIPPED, or QUARANTINED jobs can be retried: " + id);
        }
        FileRecord latest = fileRecordRepository.findBySyncJobId(id).stream()
                .max(Comparator.comparing(FileRecord::getCreatedAt))
                .orElseThrow(() -> new IllegalStateException("No file records for job: " + id));

        job.setStatus(SyncStatus.PENDING);
        syncJobRepository.save(job);

        latest.setProcessingStatus(FileProcessingStatus.DOWNLOADED);
        latest.setRowsSaved(null);
        latest.setDatabaseSavedAt(null);
        latest.setArchiveStatus(FileArchiveStatus.PENDING);
        latest.setArchivedAt(null);
        latest.setErrorMessage(null);
        fileRecordRepository.save(latest);

        permitImportRepository.findBySyncJobId(id).ifPresent(attempt -> {
            if (attempt.getStatus() == PermitImportStatus.FAILED
                    || attempt.getStatus() == PermitImportStatus.DRY_RUN
                    || attempt.getStatus() == PermitImportStatus.REVISION_REVIEW
                    || attempt.getStatus() == PermitImportStatus.RESERVED) {
                attempt.setStatus(PermitImportStatus.RESERVED);
                attempt.setTargetMasterId(null);
                attempt.setTargetPermId(null);
                attempt.setDetailCount(null);
                attempt.setErrorMessage(null);
                permitImportRepository.save(attempt);
            }
        });

        boolean priority = latest.getSourceType() == FileSourceType.EMAIL
                && latest.getOriginalFileName() != null
                && latest.getOriginalFileName().toUpperCase().contains("VIP");
        FileIngestedEvent event = new FileIngestedEvent(
                job.getId(),
                latest.getStoredPath(),
                job.getFileHash(),
                latest.getSourceType(),
                priority);
        // Queue only after the database transaction commits.  Publishing while the
        // transaction is still open lets the worker observe stale state (or race
        // with a rollback), leaving the job stuck in PENDING/IN_PROGRESS.
        publishAfterCommit(event);
    }

    private void publishAfterCommit(FileIngestedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Unit callers and non-transactional invocations still get the event.
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

    private SyncJobSummaryResponse toSummary(SyncJob job,
                                             FileRecord latest,
                                             EmailMetadata metadata) {
        String originalFileName = latest != null ? latest.getOriginalFileName() : null;

        String sender = metadata != null ? metadata.getSender() : null;
        LocalDateTime emailReceivedAt = metadata != null ? metadata.getReceivedAt() : null;
        return new SyncJobSummaryResponse(
                job.getId(),
                job.getFileHash(),
                originalFileName,
                StoredFileName.from(latest),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                sender,
                emailReceivedAt,
                latest != null ? latest.getStoredPath() : null);
    }

    private Map<Long, FileRecord> findLatestRecords(List<Long> jobIds) {
        Map<Long, FileRecord> latestRecords = new HashMap<>();
        for (int start = 0; start < jobIds.size(); start += ORACLE_IN_QUERY_BATCH_SIZE) {
            List<Long> batch = jobIds.subList(
                    start,
                    Math.min(start + ORACLE_IN_QUERY_BATCH_SIZE, jobIds.size()));
            for (FileRecord record : fileRecordRepository.findBySyncJobIdIn(batch)) {
                latestRecords.merge(
                        record.getSyncJob().getId(),
                        record,
                        (current, candidate) -> candidate.getCreatedAt().isAfter(current.getCreatedAt())
                                ? candidate
                                : current);
            }
        }
        return latestRecords;
    }

    private Map<Long, EmailMetadata> findFirstEmailMetadata(List<Long> jobIds) {
        Map<Long, EmailMetadata> firstMetadata = new HashMap<>();
        for (int start = 0; start < jobIds.size(); start += ORACLE_IN_QUERY_BATCH_SIZE) {
            List<Long> batch = jobIds.subList(
                    start,
                    Math.min(start + ORACLE_IN_QUERY_BATCH_SIZE, jobIds.size()));
            for (EmailMetadata metadata : emailMetadataRepository.findBySyncJobIdIn(batch)) {
                firstMetadata.merge(
                        metadata.getSyncJob().getId(),
                        metadata,
                        (current, candidate) -> candidate.getId() < current.getId()
                                ? candidate
                                : current);
            }
        }
        return firstMetadata;
    }

    private FileRecordResponse toFileRecordResponse(FileRecord record) {
        String sender = null;
        String subject = null;
        if (record.getSourceType() == FileSourceType.EMAIL && record.getSyncJob() != null) {
            var metadata = emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(record.getSyncJob().getId());
            if (metadata.isPresent()) {
                sender = metadata.get().getSender();
                subject = metadata.get().getSubject();
            }
        }
        return new FileRecordResponse(
                record.getId(),
                record.getSourceType(),
                record.getOriginalFileName(),
                StoredFileName.from(record),
                record.getStoredPath(),
                record.getProcessingStatus(),
                record.getRowsSaved(),
                record.getDownloadedAt(),
                record.getDatabaseSavedAt(),
                record.getArchiveStatus(),
                record.getArchivedAt(),
                errorMessageTranslator.translate(record.getErrorMessage()),
                record.getFileSize(),
                record.getChecksum(),
                record.getCreatedAt(),
                sender,
                subject,
                WordFormatRequirement.isRequired(record.getOriginalFileName(), record.getErrorMessage()));
    }

    private JobDetailLog latestDetailLog(Long syncJobId) {
        return auditLogRepository.findBySyncJobIdOrderByTimestampDesc(syncJobId).stream()
                .findFirst()
                .map(this::parseDetailLog)
                .orElse(new JobDetailLog(null, List.of()));
    }

    private JobDetailLog parseDetailLog(AuditLog auditLog) {
        String outputSummary = auditLog.getOutputSummary();
        if (outputSummary == null || outputSummary.isBlank()) {
            return new JobDetailLog(null, List.of());
        }
        try {
            return objectMapper.readValue(outputSummary, JobDetailLog.class);
        } catch (JsonProcessingException e) {
            return new JobDetailLog(outputSummary, List.of());
        }
    }

    private record JobDetailLog(String message, List<RowValidationError> rowErrors) {
        private JobDetailLog {
            rowErrors = rowErrors == null ? Collections.emptyList() : List.copyOf(rowErrors);
        }
    }
}
