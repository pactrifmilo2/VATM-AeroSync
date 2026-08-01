package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vatm.aerosync.api.config.TestReplayProperties;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.common.repository.AuditLogRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReplayServiceTest {

    @TempDir
    Path tempDir;

    private final TestReplayProperties properties = new TestReplayProperties();
    private final SyncJobRepository syncJobRepository = mock(SyncJobRepository.class);
    private final FileRecordRepository fileRecordRepository = mock(FileRecordRepository.class);
    private final EmailMetadataRepository emailMetadataRepository = mock(EmailMetadataRepository.class);
    private final PermitImportRepository permitImportRepository = mock(PermitImportRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final AtfmTestResetService atfmTestResetService = mock(AtfmTestResetService.class);
    private final JobRetryPublisher jobRetryPublisher = mock(JobRetryPublisher.class);
    private final TestReplayService service = new TestReplayService(
            properties,
            syncJobRepository,
            fileRecordRepository,
            emailMetadataRepository,
            permitImportRepository,
            auditLogRepository,
            atfmTestResetService,
            jobRetryPublisher);

    @Test
    void replay_resetsOwnedTargetAndQueuesArchivedEmailAttachment() throws Exception {
        properties.setEnabled(true);
        properties.setAtfmWriteEnabled(true);
        SyncJob job = mock(SyncJob.class);
        when(job.getStatus()).thenReturn(SyncStatus.SUCCESS);
        when(job.getFileHash()).thenReturn("hash-9");
        Path attachment = Files.writeString(tempDir.resolve("permit.docx"), "test");
        FileRecord record = record(job, attachment);
        PermitImport permitImport = permitImport(PermitImportStatus.SAVED);
        EmailMetadata metadata = new EmailMetadata();
        metadata.setSubject("VIP permit");
        when(syncJobRepository.findById(9L)).thenReturn(Optional.of(job));
        when(fileRecordRepository.findBySyncJobId(9L)).thenReturn(List.of(record));
        when(permitImportRepository.findBySyncJobId(9L)).thenReturn(Optional.of(permitImport));
        when(emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(9L)).thenReturn(Optional.of(metadata));
        when(atfmTestResetService.deleteOwnedPermit(permitImport))
                .thenReturn(new AtfmTestResetService.TargetDeleteResult(1, 2));

        var response = service.replay(9L, "LD-06/A/S/2026");

        assertThat(response.deletedTargetMasterRows()).isEqualTo(1);
        assertThat(response.deletedTargetDetailRows()).isEqualTo(2);
        assertThat(response.replayQueued()).isTrue();
        verify(permitImportRepository).saveAndFlush(permitImport);
        assertThat(permitImport.getStatus()).isEqualTo(PermitImportStatus.RESERVED);
        assertThat(permitImport.getTargetMasterId()).isNull();
        assertThat(permitImport.getTargetPermId()).isNull();
        verify(auditLogRepository).save(any());
        verify(job).setStatus(SyncStatus.PENDING);
        verify(jobRetryPublisher).publish(any(FileIngestedEvent.class));
    }

    @Test
    void replay_isDisabledByDefault() {
        assertThatThrownBy(() -> service.replay(9L, "LD-06/A/S/2026"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_TEST_REPLAY_ENABLED");

        verify(syncJobRepository, never()).findById(9L);
    }

    @Test
    void replay_rejectsWrongPermitConfirmationBeforeDeletingTarget() throws Exception {
        properties.setEnabled(true);
        properties.setAtfmWriteEnabled(true);
        SyncJob job = mock(SyncJob.class);
        when(job.getStatus()).thenReturn(SyncStatus.SUCCESS);
        Path attachment = Files.writeString(tempDir.resolve("permit.docx"), "test");
        FileRecord record = record(job, attachment);
        PermitImport permitImport = permitImport(PermitImportStatus.SAVED);
        when(syncJobRepository.findById(9L)).thenReturn(Optional.of(job));
        when(fileRecordRepository.findBySyncJobId(9L)).thenReturn(List.of(record));
        when(permitImportRepository.findBySyncJobId(9L)).thenReturn(Optional.of(permitImport));

        assertThatThrownBy(() -> service.replay(9L, "WRONG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must exactly match");

        verify(atfmTestResetService, never()).deleteOwnedPermit(any());
        verify(jobRetryPublisher, never()).publish(any());
    }

    private FileRecord record(SyncJob job, Path attachment) {
        FileRecord record = new FileRecord();
        record.setSyncJob(job);
        record.setSourceType(FileSourceType.EMAIL);
        record.setOriginalFileName("permit.docx");
        record.setStoredPath(attachment.toString());
        return record;
    }

    private PermitImport permitImport(PermitImportStatus status) {
        PermitImport permitImport = new PermitImport();
        permitImport.setNormalizedPermitId("LD-06/A/S/2026");
        permitImport.setStatus(status);
        permitImport.setTargetMasterId(10L);
        permitImport.setTargetPermId(20L);
        return permitImport;
    }
}
