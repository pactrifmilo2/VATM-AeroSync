package vatm.aerosync.common.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class SyncJobEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsAndRetrievesSyncJobById() {
        SyncJob syncJob = new SyncJob();
        syncJob.setFileHash("hash-001");

        SyncJob persisted = entityManager.persistFlushFind(syncJob);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getFileHash()).isEqualTo("hash-001");
    }

    @Test
    void statusDefaultsToPendingAndCreatedAtIsAutoPopulated() {
        SyncJob syncJob = new SyncJob();
        syncJob.setFileHash("hash-002");

        SyncJob persisted = entityManager.persistFlushFind(syncJob);

        assertThat(persisted.getStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void fileHashMustBeUnique() {
        SyncJob first = new SyncJob();
        first.setFileHash("hash-duplicate");
        entityManager.persistAndFlush(first);

        SyncJob duplicate = new SyncJob();
        duplicate.setFileHash("hash-duplicate");

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(RuntimeException.class);
    }
}
