package vatm.aerosync.common.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.testsupport.JpaTestConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = JpaTestConfiguration.class)
class FileRecordRepositoryTest {

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private FileRecordRepository repository;

    @Test
    void findsBySyncJobId() {
        SyncJob syncJob = new SyncJob();
        syncJob.setFileHash("file-record-repo-hash");

        FileRecord fileRecord = new FileRecord();
        fileRecord.setSourceType(FileSourceType.FILESYSTEM);
        fileRecord.setOriginalFileName("schedule.json");
        fileRecord.setStoredPath("/tmp/schedule.json");
        syncJob.addFileRecord(fileRecord);

        SyncJob saved = syncJobRepository.saveAndFlush(syncJob);

        List<FileRecord> records = repository.findBySyncJobId(saved.getId());

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getOriginalFileName()).isEqualTo("schedule.json");
    }
}
