package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import vatm.aerosync.api.repository.DashboardAlertRepository;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailResendCleanupServiceTest {

    @Test
    void cleanup_resetsAJobSharedByOriginalAndForwardedEmails() {
        EmailMetadataRepository metadataRepository = mock(EmailMetadataRepository.class);
        FileRecordRepository fileRepository = mock(FileRecordRepository.class);
        PermitImportRepository permitRepository = mock(PermitImportRepository.class);
        SyncJobRepository jobRepository = mock(SyncJobRepository.class);
        AuditLogRepository auditRepository = mock(AuditLogRepository.class);
        DashboardAlertRepository alertRepository = mock(DashboardAlertRepository.class);
        AtfmTestResetService atfmResetService = mock(AtfmTestResetService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        SyncJob job = mock(SyncJob.class);
        when(job.getId()).thenReturn(3189L);
        when(job.getFileHash()).thenReturn("same-sha-256");
        EmailMetadata original = metadata(job, "<original@caa.gov.vn>");
        EmailMetadata forwarded = metadata(job, "<forwarded@vatm.vn>");
        List<EmailMetadata> allMetadata = List.of(original, forwarded);

        when(jobRepository.findById(3189L)).thenReturn(Optional.of(job));
        when(metadataRepository.findBySyncJobId(3189L)).thenReturn(allMetadata);
        when(permitRepository.findBySyncJobId(3189L)).thenReturn(Optional.empty());
        when(auditRepository.findBySyncJobIdOrderByTimestampDesc(3189L)).thenReturn(List.of());
        when(alertRepository.findBySyncJobId(3189L)).thenReturn(List.of());
        when(fileRepository.findBySyncJobId(3189L)).thenReturn(List.of());

        EmailResendCleanupService service = new EmailResendCleanupService(
                metadataRepository,
                fileRepository,
                permitRepository,
                jobRepository,
                auditRepository,
                alertRepository,
                atfmResetService,
                redisTemplate);

        EmailResendCleanupService.CleanupResult result = service.cleanup(List.of(forwarded));

        assertThat(result.syncJobsDeleted()).isOne();
        verify(metadataRepository).deleteAll(allMetadata);
        verify(jobRepository).delete(job);
        verify(redisTemplate).delete("aerosync:dedup:same-sha-256");
    }

    private EmailMetadata metadata(SyncJob job, String messageId) {
        EmailMetadata metadata = mock(EmailMetadata.class);
        when(metadata.getSyncJob()).thenReturn(job);
        when(metadata.getMessageId()).thenReturn(messageId);
        return metadata;
    }
}
