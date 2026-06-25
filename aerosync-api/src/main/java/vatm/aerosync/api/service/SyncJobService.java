package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.FileRecordResponse;
import vatm.aerosync.api.dto.SyncJobDetailResponse;
import vatm.aerosync.api.dto.SyncJobSummaryResponse;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SyncJobService {

    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final EmailMetadataRepository emailMetadataRepository;
    private final JobRetryPublisher jobRetryPublisher;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public SyncJobService(SyncJobRepository syncJobRepository,
                          FileRecordRepository fileRecordRepository,
                          EmailMetadataRepository emailMetadataRepository,
                          JobRetryPublisher jobRetryPublisher,
                          AuditLogRepository auditLogRepository) {
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.emailMetadataRepository = emailMetadataRepository;
        this.jobRetryPublisher = jobRetryPublisher;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public List<SyncJobSummaryResponse> listJobs(SyncStatus statusFilter) {
        List<SyncJob> jobs = statusFilter == null
                ? syncJobRepository.findAll()
                : syncJobRepository.findByStatus(statusFilter);
        return jobs.stream()
                .sorted(Comparator.comparing(SyncJob::getCreatedAt).reversed())
                .map(this::toSummary)
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
        return new SyncJobDetailResponse(
                job.getId(),
                job.getFileHash(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                records,
                latestLog.rowErrors(),
                latestLog.message());
    }

    @Transactional
    public void retryJob(Long id) {
        SyncJob job = syncJobRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Sync job not found: " + id));
        FileRecord latest = fileRecordRepository.findBySyncJobId(id).stream()
                .max(Comparator.comparing(FileRecord::getCreatedAt))
                .orElseThrow(() -> new IllegalStateException("No file records for job: " + id));

        job.setStatus(SyncStatus.PENDING);
        syncJobRepository.save(job);

        boolean priority = latest.getSourceType() == FileSourceType.EMAIL
                && latest.getOriginalFileName() != null
                && latest.getOriginalFileName().toUpperCase().contains("VIP");
        FileIngestedEvent event = new FileIngestedEvent(
                job.getId(),
                latest.getStoredPath(),
                job.getFileHash(),
                latest.getSourceType(),
                priority);
        jobRetryPublisher.publish(event);
    }

    private SyncJobSummaryResponse toSummary(SyncJob job) {
        String originalFileName = fileRecordRepository.findBySyncJobId(job.getId()).stream()
                .max(Comparator.comparing(FileRecord::getCreatedAt))
                .map(FileRecord::getOriginalFileName)
                .orElse(null);
        return new SyncJobSummaryResponse(
                job.getId(),
                job.getFileHash(),
                originalFileName,
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }

    private FileRecordResponse toFileRecordResponse(FileRecord record) {
        String sender = null;
        String subject = null;
        if (record.getSourceType() == FileSourceType.EMAIL && record.getSyncJob() != null) {
            var metadata = emailMetadataRepository.findBySyncJobId(record.getSyncJob().getId());
            if (metadata.isPresent()) {
                sender = metadata.get().getSender();
                subject = metadata.get().getSubject();
            }
        }
        return new FileRecordResponse(
                record.getId(),
                record.getSourceType(),
                record.getOriginalFileName(),
                record.getStoredPath(),
                record.getCreatedAt(),
                sender,
                subject);
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
