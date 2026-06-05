package vatm.aerosync.common.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class AuditLogEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsAuditLogFields() {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction("SYNC_SUCCESS");
        auditLog.setInputSummary("input file");
        auditLog.setOutputSummary("10 records");
        auditLog.setDurationMs(250L);
        auditLog.setResultStatus(SyncStatus.SUCCESS);

        AuditLog persisted = entityManager.persistFlushFind(auditLog);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getAction()).isEqualTo("SYNC_SUCCESS");
        assertThat(persisted.getInputSummary()).isEqualTo("input file");
        assertThat(persisted.getOutputSummary()).isEqualTo("10 records");
        assertThat(persisted.getDurationMs()).isEqualTo(250L);
        assertThat(persisted.getResultStatus()).isEqualTo(SyncStatus.SUCCESS);
    }

    @Test
    void timestampIsAutoSet() {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction("SYNC_STARTED");
        auditLog.setResultStatus(SyncStatus.IN_PROGRESS);

        AuditLog persisted = entityManager.persistFlushFind(auditLog);

        assertThat(persisted.getTimestamp()).isNotNull();
    }
}
