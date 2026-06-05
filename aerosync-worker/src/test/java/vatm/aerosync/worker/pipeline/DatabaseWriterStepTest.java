package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.entity.FlightData;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.repository.FlightDataRepository;
import vatm.aerosync.worker.testsupport.WorkerJpaTestConfiguration;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ContextConfiguration(classes = WorkerJpaTestConfiguration.class)
@Import(DatabaseWriterStep.class)
@ActiveProfiles("test")
class DatabaseWriterStepTest {

    @Autowired
    private DatabaseWriterStep databaseWriterStep;

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private FlightDataRepository flightDataRepository;

    @Test
    void write_persistsRowsAndMarksJobSuccess() {
        SyncJob job = new SyncJob();
        job.setFileHash("abc123");
        job.setStatus(SyncStatus.IN_PROGRESS);
        SyncJob saved = syncJobRepository.save(job);

        ProcessingContext context = new ProcessingContext(
                new FileIngestedEvent(saved.getId(), "/tmp/f.csv", "abc123", FileSourceType.FILESYSTEM, false));
        context.getRows().add(new FlightRow("VN123", "HAN", "SGN", LocalDate.of(2026, 6, 1)));

        databaseWriterStep.write(context);

        List<FlightData> rows = flightDataRepository.findBySyncJobId(saved.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getCallsign()).isEqualTo("VN123");
        assertThat(syncJobRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(SyncStatus.SUCCESS);
    }
}
