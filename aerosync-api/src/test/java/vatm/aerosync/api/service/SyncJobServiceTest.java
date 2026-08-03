package vatm.aerosync.api.service;
import org.junit.jupiter.api.Test;
import vatm.aerosync.api.dto.SyncJobSummaryResponse;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.common.repository.PermitImportRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncJobServiceTest {

    private final SyncJobRepository syncJobRepository = mock(SyncJobRepository.class);
    private final FileRecordRepository fileRecordRepository = mock(FileRecordRepository.class);
    private final EmailMetadataRepository emailMetadataRepository = mock(EmailMetadataRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final JobRetryPublisher jobRetryPublisher = mock(JobRetryPublisher.class);
    private final PermitImportRepository permitImportRepository = mock(PermitImportRepository.class);
    private final VietnameseErrorMessageTranslator errorMessageTranslator =
            new VietnameseErrorMessageTranslator();
    private final SyncJobService service = new SyncJobService(
            syncJobRepository,
            fileRecordRepository,
            emailMetadataRepository,
            jobRetryPublisher,
            auditLogRepository,
            permitImportRepository,
            errorMessageTranslator);

    @Test
    void listJobs_includesOriginalFileNameAndSenderFromLatestFileRecord() {
        SyncJob job = mock(SyncJob.class);
        when(job.getId()).thenReturn(12L);
        when(job.getFileHash()).thenReturn("hash-12");
        when(job.getStatus()).thenReturn(SyncStatus.FAILED);
        FileRecord record = new FileRecord();
        record.setOriginalFileName("data.csv");
        record.setStoredPath("C:\\vatm-storage\\error\\2026\\07\\28\\operator_20260728_091500_email_data.csv");
        record.setSourceType(FileSourceType.EMAIL);
        record.setSyncJob(job);
        when(syncJobRepository.findAll()).thenReturn(List.of(job));
        when(fileRecordRepository.findBySyncJobIdIn(List.of(12L))).thenReturn(List.of(record));
        // No EmailMetadata — sender and emailReceivedAt should be null
        when(emailMetadataRepository.findBySyncJobIdIn(List.of(12L))).thenReturn(List.of());

        List<SyncJobSummaryResponse> summaries = service.listJobs(null);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().originalFileName()).isEqualTo("data.csv");
        assertThat(summaries.getFirst().storedFileName())
                .isEqualTo("operator_20260728_091500_email_data.csv");
        assertThat(summaries.getFirst().sender()).isNull();
        assertThat(summaries.getFirst().emailReceivedAt()).isNull();
        verify(fileRecordRepository, never()).findBySyncJobId(12L);
        verify(emailMetadataRepository, never()).findFirstBySyncJobIdOrderByIdAsc(12L);
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
        when(emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(7L)).thenReturn(Optional.empty());
        when(permitImportRepository.findBySyncJobId(7L)).thenReturn(Optional.empty());

        var response = service.getJob(7L);

        assertThat(response.latestLogMessage()).isEqualTo("BR-CALLSIGN: Row 1: Invalid callsign");
        assertThat(response.rowErrors())
                .extracting("rowNumber", "field", "code", "value")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "callsign", "BR-CALLSIGN", "!"));
    }

    @Test
    void getJob_includesPermitImportOutcome() {
        SyncJob job = new SyncJob();
        job.setFileHash("permit-hash");
        job.setStatus(SyncStatus.SUCCESS);
        PermitImport permitImport = new PermitImport();
        permitImport.setNormalizedPermitId("O/F 05199/S/CHK/2026");
        permitImport.setStatus(PermitImportStatus.SAVED);
        permitImport.setTargetMasterId(101L);
        permitImport.setTargetPermId(202L);
        permitImport.setDetailCount(1);
        when(syncJobRepository.findById(9L)).thenReturn(Optional.of(job));
        when(fileRecordRepository.findBySyncJobId(9L)).thenReturn(List.of());
        when(auditLogRepository.findBySyncJobIdOrderByTimestampDesc(9L)).thenReturn(List.of());
        when(emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(9L)).thenReturn(Optional.empty());
        when(permitImportRepository.findBySyncJobId(9L)).thenReturn(Optional.of(permitImport));

        var response = service.getJob(9L);

        assertThat(response.permitImportStatus()).isEqualTo(PermitImportStatus.SAVED);
        assertThat(response.normalizedPermitId()).isEqualTo("O/F 05199/S/CHK/2026");
        assertThat(response.targetMasterId()).isEqualTo(101L);
        assertThat(response.targetPermId()).isEqualTo(202L);
        assertThat(response.permitDetailCount()).isEqualTo(1);
    }
}
