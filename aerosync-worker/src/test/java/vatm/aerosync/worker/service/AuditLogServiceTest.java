package vatm.aerosync.worker.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.testsupport.WorkerJpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = WorkerJpaTestConfiguration.class)
@Import(AuditLogService.class)
@ActiveProfiles("test")
class AuditLogServiceTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Test
    void record_persistsRequiredAuditFields() {
        SyncJob job = new SyncJob();
        job.setFileHash("hash-1");
        SyncJob saved = syncJobRepository.save(job);

        auditLogService.record(saved.getId(), "SYNC_SUCCESS", "input", "output", SyncStatus.SUCCESS, 42L);

        AuditLog log = auditLogRepository.findAll().getFirst();
        assertThat(log.getAction()).isEqualTo("SYNC_SUCCESS");
        assertThat(log.getInputSummary()).isEqualTo("input");
        assertThat(log.getOutputSummary()).isEqualTo("output");
        assertThat(log.getDurationMs()).isEqualTo(42L);
        assertThat(log.getResultStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(log.getSyncJob().getId()).isEqualTo(saved.getId());
        assertThat(log.getTimestamp()).isNotNull();
    }
}
