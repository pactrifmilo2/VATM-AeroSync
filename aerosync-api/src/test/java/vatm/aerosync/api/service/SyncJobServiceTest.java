package vatm.aerosync.api.service;
import org.junit.jupiter.api.Test;
import vatm.aerosync.api.dto.SyncJobSummaryResponse;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncJobServiceTest {

    private final SyncJobRepository syncJobRepository = mock(SyncJobRepository.class);
    private final FileRecordRepository fileRecordRepository = mock(FileRecordRepository.class);
    private final EmailMetadataRepository emailMetadataRepository = mock(EmailMetadataRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final JobRetryPublisher jobRetryPublisher = mock(JobRetryPublisher.class);
    private final SyncJobService service = new SyncJobService(
            syncJobRepository,
            fileRecordRepository,
            emailMetadataRepository,
            jobRetryPublisher,
            auditLogRepository);

    @Test
    void listJobs_includesOriginalFileNameAndSenderFromLatestFileRecord() {
        SyncJob job = mock(SyncJob.class);
        when(job.getId()).thenReturn(12L);
        when(job.getFileHash()).thenReturn("hash-12");
        when(job.getStatus()).thenReturn(SyncStatus.FAILED);
        FileRecord record = new FileRecord();
        record.setOriginalFileName("data.csv");
        record.setSourceType(FileSourceType.EMAIL);
        when(syncJobRepository.findAll()).thenReturn(List.of(job));
        when(fileRecordRepository.findBySyncJobId(12L)).thenReturn(List.of(record));
        // No EmailMetadata — sender and emailReceivedAt should be null
        when(emailMetadataRepository.findBySyncJobId(12L)).thenReturn(java.util.Optional.empty());

        List<SyncJobSummaryResponse> summaries = service.listJobs(null);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().originalFileName()).isEqualTo("data.csv");
        assertThat(summaries.getFirst().sender()).isNull();
        assertThat(summaries.getFirst().emailReceivedAt()).isNull();
    }

    @Test
    void getJob_includesRowErrorsFromLatestAuditOutput() {
        SyncJob job = new SyncJob();
        job.setFileHash("hash-7");
        job.setStatus(SyncStatus.QUARANTINED);
        AuditLog auditLog = new AuditLog();
        auditLog.setOutputSummary("""
                {"message":"BR-CALLSIGN: Row 1: Invalid callsign","rowErrors":[
                  {"rowNumber":1,"field":"callsign","code":"BR-CALLSIGN","message":"Invalid callsign","value":"!"}
                ]}
                """);
        when(syncJobRepository.findById(7L)).thenReturn(Optional.of(job));
        when(fileRecordRepository.findBySyncJobId(7L)).thenReturn(List.of());
        when(auditLogRepository.findBySyncJobIdOrderByTimestampDesc(7L)).thenReturn(List.of(auditLog));

        var response = service.getJob(7L);

        assertThat(response.latestLogMessage()).isEqualTo("BR-CALLSIGN: Row 1: Invalid callsign");
        assertThat(response.rowErrors())
                .extracting("rowNumber", "field", "code", "value")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "callsign", "BR-CALLSIGN", "!"));
    }
}
