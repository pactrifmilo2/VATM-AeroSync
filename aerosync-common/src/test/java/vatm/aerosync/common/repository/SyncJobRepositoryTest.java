package vatm.aerosync.common.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class SyncJobRepositoryTest {

    @Autowired
    private SyncJobRepository repository;

    @Test
    void findsByFileHash() {
        SyncJob syncJob = new SyncJob();
        syncJob.setFileHash("repo-hash-001");
        repository.saveAndFlush(syncJob);

        Optional<SyncJob> found = repository.findByFileHash("repo-hash-001");
        Optional<SyncJob> missing = repository.findByFileHash("missing-hash");

        assertThat(found).isPresent();
        assertThat(found.get().getFileHash()).isEqualTo("repo-hash-001");
        assertThat(missing).isEmpty();
    }

    @Test
    void findsByStatus() {
        SyncJob success = new SyncJob();
        success.setFileHash("repo-status-success");
        success.setStatus(SyncStatus.SUCCESS);
        repository.save(success);

        SyncJob failed = new SyncJob();
        failed.setFileHash("repo-status-failed");
        failed.setStatus(SyncStatus.FAILED);
        repository.saveAndFlush(failed);

        List<SyncJob> results = repository.findByStatus(SyncStatus.SUCCESS);

        assertThat(results).extracting(SyncJob::getFileHash)
                .contains("repo-status-success")
                .doesNotContain("repo-status-failed");
    }
}
