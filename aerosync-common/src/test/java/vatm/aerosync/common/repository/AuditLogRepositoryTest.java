package vatm.aerosync.common.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository repository;

    @Test
    void findsByTimestampBetween() {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction("SYNC_SUCCESS");
        auditLog.setResultStatus(SyncStatus.SUCCESS);
        repository.saveAndFlush(auditLog);

        LocalDateTime from = LocalDateTime.now().minusMinutes(1);
        LocalDateTime to = LocalDateTime.now().plusMinutes(1);

        List<AuditLog> results = repository.findByTimestampBetween(from, to);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getAction()).isEqualTo("SYNC_SUCCESS");
    }

    @Test
    void findsByResultStatus() {
        AuditLog success = new AuditLog();
        success.setAction("SYNC_SUCCESS");
        success.setResultStatus(SyncStatus.SUCCESS);
        repository.save(success);

        AuditLog failed = new AuditLog();
        failed.setAction("SYNC_FAILED");
        failed.setResultStatus(SyncStatus.FAILED);
        repository.saveAndFlush(failed);

        List<AuditLog> results = repository.findByResultStatus(SyncStatus.FAILED);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getAction()).isEqualTo("SYNC_FAILED");
    }
}
