package vatm.aerosync.common.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class FileRecordEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsFileRecordWithSyncJobForeignKey() {
        SyncJob syncJob = new SyncJob();
        syncJob.setFileHash("file-record-hash");
        entityManager.persistAndFlush(syncJob);

        FileRecord fileRecord = new FileRecord();
        fileRecord.setSyncJob(syncJob);
        fileRecord.setSourceType(FileSourceType.EMAIL);
        fileRecord.setOriginalFileName("flight-plan.csv");
        fileRecord.setStoredPath("/tmp/flight-plan.csv");

        FileRecord persisted = entityManager.persistFlushFind(fileRecord);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getSourceType()).isEqualTo(FileSourceType.EMAIL);
        assertThat(persisted.getOriginalFileName()).isEqualTo("flight-plan.csv");
        assertThat(persisted.getStoredPath()).isEqualTo("/tmp/flight-plan.csv");
        assertThat(persisted.getSyncJob().getId()).isEqualTo(syncJob.getId());
    }

    @Test
    void syncJobCascadesFileRecords() {
        SyncJob syncJob = new SyncJob();
        syncJob.setFileHash("cascade-hash");

        FileRecord fileRecord = new FileRecord();
        fileRecord.setSourceType(FileSourceType.FILESYSTEM);
        fileRecord.setOriginalFileName("ops.json");
        fileRecord.setStoredPath("/tmp/ops.json");

        syncJob.addFileRecord(fileRecord);

        SyncJob persisted = entityManager.persistFlushFind(syncJob);

        assertThat(persisted.getFileRecords()).hasSize(1);
        assertThat(persisted.getFileRecords().getFirst().getSyncJob().getId()).isEqualTo(persisted.getId());
    }
}
