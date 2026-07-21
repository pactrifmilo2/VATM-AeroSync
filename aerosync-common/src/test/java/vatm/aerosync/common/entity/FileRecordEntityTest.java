package vatm.aerosync.common.entity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import java.time.LocalDateTime;

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
        fileRecord.setProcessingStatus(FileProcessingStatus.SAVED);
        fileRecord.setRowsSaved(25);
        fileRecord.setDownloadedAt(LocalDateTime.of(2026, 7, 18, 10, 0));
        fileRecord.setDatabaseSavedAt(LocalDateTime.of(2026, 7, 18, 10, 1));
        fileRecord.setArchiveStatus(FileArchiveStatus.ARCHIVED);
        fileRecord.setArchivedAt(LocalDateTime.of(2026, 7, 18, 10, 2));
        fileRecord.setFileSize(4096L);
        fileRecord.setChecksum("a".repeat(64));

        FileRecord persisted = entityManager.persistFlushFind(fileRecord);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getSourceType()).isEqualTo(FileSourceType.EMAIL);
        assertThat(persisted.getOriginalFileName()).isEqualTo("flight-plan.csv");
        assertThat(persisted.getStoredPath()).isEqualTo("/tmp/flight-plan.csv");
        assertThat(persisted.getProcessingStatus()).isEqualTo(FileProcessingStatus.SAVED);
        assertThat(persisted.getRowsSaved()).isEqualTo(25);
        assertThat(persisted.getDownloadedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0));
        assertThat(persisted.getDatabaseSavedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 1));
        assertThat(persisted.getArchiveStatus()).isEqualTo(FileArchiveStatus.ARCHIVED);
        assertThat(persisted.getArchivedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 2));
        assertThat(persisted.getFileSize()).isEqualTo(4096L);
        assertThat(persisted.getChecksum()).isEqualTo("a".repeat(64));
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
        FileRecord persistedRecord = persisted.getFileRecords().getFirst();
        assertThat(persistedRecord.getSyncJob().getId()).isEqualTo(persisted.getId());
        assertThat(persistedRecord.getProcessingStatus()).isEqualTo(FileProcessingStatus.DISCOVERED);
        assertThat(persistedRecord.getArchiveStatus()).isEqualTo(FileArchiveStatus.PENDING);
    }
}
