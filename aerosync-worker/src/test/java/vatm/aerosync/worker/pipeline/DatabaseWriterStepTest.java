package vatm.aerosync.worker.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.entity.FlightData;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.ScheduleFlight;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.repository.FlightDataRepository;
import vatm.aerosync.worker.testsupport.WorkerJpaTestConfiguration;
import vatm.aerosync.worker.service.PermitImportCoordinator;
import vatm.aerosync.worker.service.PermitImportOutcome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@ContextConfiguration(classes = WorkerJpaTestConfiguration.class)
@Import(DatabaseWriterStep.class)
@ActiveProfiles("test")
class DatabaseWriterStepTest {

    @Autowired
    private DatabaseWriterStep databaseWriterStep;

    @MockitoBean
    private PermitImportCoordinator permitImportCoordinator;

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private FlightDataRepository flightDataRepository;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Test
    void write_persistsRowsAndMarksJobSuccess() {
        TestFixture fixture = createFixture("batch-success");
        ProcessingContext context = contextFor(fixture);
        context.getRows().add(new FlightRow("VN123", "HAN", "SGN", LocalDate.of(2026, 6, 1)));
        context.getRows().add(new FlightRow("VN456", "DAD", "HAN", LocalDate.of(2026, 6, 2)));

        databaseWriterStep.write(context);

        List<FlightData> rows = flightDataRepository.findBySyncJobId(fixture.job().getId());
        assertThat(rows)
                .hasSize(2)
                .extracting(FlightData::getCallsign)
                .containsExactlyInAnyOrder("VN123", "VN456");
        assertSaved(fixture, 2);
    }

    @Test
    void write_marksValidEmptyFileAsSavedWithZeroRows() {
        TestFixture fixture = createFixture("empty-success");

        databaseWriterStep.write(contextFor(fixture));

        assertThat(flightDataRepository.findBySyncJobId(fixture.job().getId())).isEmpty();
        assertSaved(fixture, 0);
    }

    @Test
    void write_retryReplacesRowsWithoutDuplicates() {
        TestFixture fixture = createFixture("retry-replace");
        ProcessingContext firstAttempt = contextFor(fixture);
        firstAttempt.getRows().add(new FlightRow("VN111", "HAN", "SGN", LocalDate.of(2026, 6, 1)));
        firstAttempt.getRows().add(new FlightRow("VN222", "SGN", "DAD", LocalDate.of(2026, 6, 1)));
        databaseWriterStep.write(firstAttempt);

        fixture.job().setStatus(SyncStatus.IN_PROGRESS);
        syncJobRepository.save(fixture.job());
        fixture.fileRecord().setProcessingStatus(FileProcessingStatus.PROCESSING);
        fileRecordRepository.save(fixture.fileRecord());

        ProcessingContext retry = contextFor(fixture);
        retry.getRows().add(new FlightRow("VN333", "DAD", "HAN", LocalDate.of(2026, 6, 2)));

        databaseWriterStep.write(retry);

        List<FlightData> rows = flightDataRepository.findBySyncJobId(fixture.job().getId());
        assertThat(rows)
                .singleElement()
                .extracting(FlightData::getCallsign)
                .isEqualTo("VN333");
        assertSaved(fixture, 1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void write_rollsBackRowsAndStatusesWhenBatchFails() {
        TestFixture fixture = createFixture("batch-rollback");
        ProcessingContext context = contextFor(fixture);
        context.getRows().add(new FlightRow("VN123", "HAN", "SGN", LocalDate.of(2026, 6, 1)));
        context.getRows().add(new FlightRow(null, "DAD", "HAN", LocalDate.of(2026, 6, 2)));

        assertThatThrownBy(() -> databaseWriterStep.write(context))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(flightDataRepository.findBySyncJobId(fixture.job().getId())).isEmpty();
        SyncJob unchangedJob = syncJobRepository.findById(fixture.job().getId()).orElseThrow();
        assertThat(unchangedJob.getStatus()).isEqualTo(SyncStatus.IN_PROGRESS);
        FileRecord unchangedRecord = fileRecordRepository.findBySyncJobId(fixture.job().getId()).getFirst();
        assertThat(unchangedRecord.getProcessingStatus()).isEqualTo(FileProcessingStatus.PROCESSING);
        assertThat(unchangedRecord.getRowsSaved()).isNull();
        assertThat(unchangedRecord.getDatabaseSavedAt()).isNull();
    }

    @Test
    void write_schedulePermitMarksFileAndJobSaved() {
        TestFixture fixture = createFixture("permit-success");
        ProcessingContext context = contextFor(fixture);
        context.setSchedulePermit(schedulePermit());
        when(permitImportCoordinator.importPermit(any()))
                .thenReturn(new PermitImportOutcome(PermitImportStatus.SAVED, 1, 101L, 202L));

        DatabaseWriteResult result = databaseWriterStep.write(context);

        assertThat(result.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(result.rowsSaved()).isEqualTo(1);
        assertSaved(fixture, 1);
    }

    @Test
    void write_duplicateSchedulePermitMarksFileAndJobSkipped() {
        TestFixture fixture = createFixture("permit-duplicate");
        ProcessingContext context = contextFor(fixture);
        context.setSchedulePermit(schedulePermit());
        when(permitImportCoordinator.importPermit(any()))
                .thenReturn(new PermitImportOutcome(PermitImportStatus.DUPLICATE, 1, 101L, 202L));

        DatabaseWriteResult result = databaseWriterStep.write(context);

        assertThat(result.status()).isEqualTo(SyncStatus.SKIPPED);
        assertThat(result.rowsSaved()).isEqualTo(1);
        assertThat(syncJobRepository.findById(fixture.job().getId()).orElseThrow().getStatus())
                .isEqualTo(SyncStatus.SKIPPED);
        FileRecord record = fileRecordRepository.findBySyncJobId(fixture.job().getId()).getFirst();
        assertThat(record.getProcessingStatus()).isEqualTo(FileProcessingStatus.SKIPPED);
        assertThat(record.getRowsSaved()).isEqualTo(1);
        assertThat(record.getDatabaseSavedAt()).isNotNull();
    }

    private TestFixture createFixture(String hash) {
        SyncJob job = new SyncJob();
        job.setFileHash(hash);
        job.setStatus(SyncStatus.IN_PROGRESS);
        SyncJob saved = syncJobRepository.save(job);

        FileRecord fileRecord = new FileRecord();
        fileRecord.setSyncJob(saved);
        fileRecord.setSourceType(FileSourceType.FILESYSTEM);
        fileRecord.setOriginalFileName("f.csv");
        fileRecord.setStoredPath("/tmp/f.csv");
        fileRecord.setProcessingStatus(FileProcessingStatus.PROCESSING);
        FileRecord savedRecord = fileRecordRepository.save(fileRecord);
        return new TestFixture(saved, savedRecord);
    }

    private ProcessingContext contextFor(TestFixture fixture) {
        return new ProcessingContext(new FileIngestedEvent(
                fixture.job().getId(),
                "/tmp/f.csv",
                fixture.job().getFileHash(),
                FileSourceType.FILESYSTEM,
                false));
    }

    private void assertSaved(TestFixture fixture, int expectedRows) {
        assertThat(syncJobRepository.findById(fixture.job().getId()).orElseThrow().getStatus())
                .isEqualTo(SyncStatus.SUCCESS);
        FileRecord updatedRecord = fileRecordRepository.findBySyncJobId(fixture.job().getId()).getFirst();
        assertThat(updatedRecord.getProcessingStatus()).isEqualTo(FileProcessingStatus.SAVED);
        assertThat(updatedRecord.getRowsSaved()).isEqualTo(expectedRows);
        assertThat(updatedRecord.getDatabaseSavedAt()).isNotNull();
    }

    private SchedulePermit schedulePermit() {
        ScheduleFlight flight = new ScheduleFlight(
                "A", 1935L, BigDecimal.ZERO, "RMY685", "", "1000000",
                "WMKK", "VHHH", "1140", null, "M765/M771",
                LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 27), "CAR 76X/32X");
        return new SchedulePermit(
                "OF-5199/7/2026VN", "O/F 05199/S/CHK/2026", "5199",
                "CHK", "O/F", "A", "S", LocalDate.of(2026, 7, 17),
                "RMY", "G17.44-260715-170787", 72, "Cyberjaya, Malaysia",
                "SC", "permit", List.of(flight));
    }

    private record TestFixture(SyncJob job, FileRecord fileRecord) {
    }
}
