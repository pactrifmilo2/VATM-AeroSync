package vatm.aerosync.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.FileRecordResponse;
import vatm.aerosync.api.dto.SyncJobDetailResponse;
import vatm.aerosync.api.dto.SyncJobSummaryResponse;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SyncJobService {

    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final JobRetryPublisher jobRetryPublisher;

    public SyncJobService(SyncJobRepository syncJobRepository,
                          FileRecordRepository fileRecordRepository,
                          JobRetryPublisher jobRetryPublisher) {
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.jobRetryPublisher = jobRetryPublisher;
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
        return new SyncJobDetailResponse(
                job.getId(),
                job.getFileHash(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                records);
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
        return new SyncJobSummaryResponse(
                job.getId(),
                job.getFileHash(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }

    private FileRecordResponse toFileRecordResponse(FileRecord record) {
        return new FileRecordResponse(
                record.getId(),
                record.getSourceType(),
                record.getOriginalFileName(),
                record.getStoredPath(),
                record.getCreatedAt());
    }
}
