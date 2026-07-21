package vatm.aerosync.common.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class PermitImportEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsPermitImportTrackingFields() {
        SyncJob job = new SyncJob();
        job.setFileHash("a".repeat(64));
        job.setStatus(SyncStatus.SUCCESS);
        entityManager.persist(job);

        PermitImport permitImport = new PermitImport();
        permitImport.setSyncJob(job);
        permitImport.setNormalizedPermitId("O/F 05199/S/CHK/2026");
        permitImport.setSemanticHash("b".repeat(64));
        permitImport.setSourceFileHash(job.getFileHash());
        permitImport.setStatus(PermitImportStatus.SAVED);
        permitImport.setTargetMasterId(203001L);
        permitImport.setTargetPermId(202510L);
        permitImport.setDetailCount(1);

        PermitImport persisted = entityManager.persistFlushFind(permitImport);

        assertThat(persisted.getStatus()).isEqualTo(PermitImportStatus.SAVED);
        assertThat(persisted.getNormalizedPermitId()).isEqualTo("O/F 05199/S/CHK/2026");
        assertThat(persisted.getTargetMasterId()).isEqualTo(203001L);
        assertThat(persisted.getTargetPermId()).isEqualTo(202510L);
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }
}
