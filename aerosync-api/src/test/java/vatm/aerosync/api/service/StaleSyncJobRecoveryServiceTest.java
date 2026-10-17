package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StaleSyncJobRecoveryServiceTest {
    private final SyncJobRepository jobs = mock(SyncJobRepository.class);
    private final FileRecordRepository records = mock(FileRecordRepository.class);
    private final JobRetryPublisher publisher = mock(JobRetryPublisher.class);

    @Test
    void republishesStalePendingJobFromLatestRecord(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        SyncJob job = pendingJob();
        FileRecord record = recordFor(job, java.nio.file.Files.writeString(
                tempDir.resolve("permit.docx"), "x").toString());
        when(jobs.findByStatusAndUpdatedAtBefore(eq(SyncStatus.PENDING), any())).thenReturn(List.of(job));
        when(records.findBySyncJobId(any())).thenReturn(List.of(record));

        var service = new StaleSyncJobRecoveryService(jobs, records, publisher, 15);
        assertThat(service.recoverStalePendingJobs(LocalDateTime.now())).isEqualTo(1);
        verify(publisher).publish(any());
    }

    @Test
    void skipsStalePendingJobWhoseArchivedFileIsGone() {
        SyncJob job = pendingJob();
        FileRecord record = recordFor(job, "C:/missing/permit.docx");
        when(jobs.findByStatusAndUpdatedAtBefore(eq(SyncStatus.PENDING), any())).thenReturn(List.of(job));
        when(records.findBySyncJobId(any())).thenReturn(List.of(record));

        var service = new StaleSyncJobRecoveryService(jobs, records, publisher, 15);
        assertThat(service.recoverStalePendingJobs(LocalDateTime.now())).isZero();
        verify(publisher, never()).publish(any());
    }

    private SyncJob pendingJob() {
        SyncJob job = new SyncJob();
        job.setFileHash("hash");
        job.setStatus(SyncStatus.PENDING);
        return job;
    }

    private FileRecord recordFor(SyncJob job, String storedPath) {
        FileRecord record = new FileRecord();
        record.setSyncJob(job);
        record.setStoredPath(storedPath);
        record.setSourceType(FileSourceType.FILESYSTEM);
        return record;
    }
}
