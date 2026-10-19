package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vatm.aerosync.api.dto.AuditLogResponse;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.FileRecordRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogQueryServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private FileRecordRepository fileRecordRepository;

    @Test
    void exposesDetailedAtfmWriteAuditMessage() {
        SyncJob syncJob = org.mockito.Mockito.mock(SyncJob.class);
        when(syncJob.getId()).thenReturn(7L);
        AuditLog log = org.mockito.Mockito.mock(AuditLog.class);
        when(log.getSyncJob()).thenReturn(syncJob);
        when(log.getId()).thenReturn(1L);
        when(log.getAction()).thenReturn("ATFM_INSERT_FAILED");
        when(log.getResultStatus()).thenReturn(SyncStatus.FAILED);
        when(log.getDurationMs()).thenReturn(42L);
        when(log.getTimestamp()).thenReturn(LocalDateTime.of(2026, 10, 19, 16, 0));
        when(log.getOutputSummary()).thenReturn("result=FAILED; error=ORA-00001");
        when(auditLogRepository.findTop500ByOrderByTimestampDesc()).thenReturn(List.of(log));
        when(fileRecordRepository.findBySyncJobIdIn(List.of(7L))).thenReturn(List.of());

        AuditLogQueryService service = new AuditLogQueryService(
                auditLogRepository, fileRecordRepository, new VietnameseErrorMessageTranslator());

        List<AuditLogResponse> responses = service.search(null, null, null, null, 10);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().action()).isEqualTo("ATFM_INSERT_FAILED");
        assertThat(responses.getFirst().message()).isEqualTo("result=FAILED; error=ORA-00001");
    }

    @Test
    void doesNotExposeOutputDetailsForOrdinaryAuditActions() {
        AuditLog log = org.mockito.Mockito.mock(AuditLog.class);
        when(log.getAction()).thenReturn("SYNC_SUCCESS");
        when(log.getResultStatus()).thenReturn(SyncStatus.SUCCESS);
        when(log.getTimestamp()).thenReturn(LocalDateTime.of(2026, 10, 19, 16, 0));
        when(auditLogRepository.findTop500ByOrderByTimestampDesc()).thenReturn(List.of(log));

        AuditLogQueryService service = new AuditLogQueryService(
                auditLogRepository, fileRecordRepository, new VietnameseErrorMessageTranslator());

        List<AuditLogResponse> responses = service.search(null, null, null, null, 10);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().message()).isNull();
    }
}
