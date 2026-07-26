package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.entity.FlightData;
import vatm.aerosync.worker.model.FlightRow;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.repository.FlightDataRepository;
import vatm.aerosync.worker.service.PermitImportCoordinator;
import vatm.aerosync.worker.service.PermitImportOutcome;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class DatabaseWriterStep {

    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final FlightDataRepository flightDataRepository;
    private final PermitImportCoordinator permitImportCoordinator;
    private final TransactionTemplate transactionTemplate;

    public DatabaseWriterStep(SyncJobRepository syncJobRepository,
                              FileRecordRepository fileRecordRepository,
                              FlightDataRepository flightDataRepository,
                              PermitImportCoordinator permitImportCoordinator,
                              PlatformTransactionManager transactionManager) {
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.flightDataRepository = flightDataRepository;
        this.permitImportCoordinator = permitImportCoordinator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public DatabaseWriteResult write(ProcessingContext context) {
        if (context.getSchedulePermit() != null) {
            return writeSchedulePermit(context);
        }
        return writeFlightRows(context);
    }

    DatabaseWriteResult writeFlightRows(ProcessingContext context) {
        DatabaseWriteResult result = transactionTemplate.execute(status -> writeFlightRowsInTransaction(context));
        if (result == null) {
            throw new IllegalStateException("Flight row transaction returned no result");
        }
        return result;
    }

    private DatabaseWriteResult writeFlightRowsInTransaction(ProcessingContext context) {
        Long syncJobId = context.getEvent().getSyncJobId();
        SyncJob job = syncJobRepository.findById(syncJobId)
                .orElseThrow(() -> new IllegalStateException("Sync job not found: " + syncJobId));

        flightDataRepository.deleteBySyncJobId(syncJobId);

        List<FlightData> entities = context.getRows().stream()
                .map(row -> toEntity(syncJobId, row))
                .toList();
        int rowsSaved = flightDataRepository.saveAllAndFlush(entities).size();

        LocalDateTime savedAt = LocalDateTime.now();
        List<FileRecord> fileRecords = fileRecordRepository.findBySyncJobId(syncJobId);
        for (FileRecord record : fileRecords) {
            record.setProcessingStatus(FileProcessingStatus.SAVED);
            record.setRowsSaved(rowsSaved);
            record.setDatabaseSavedAt(savedAt);
            record.setErrorMessage(null);
        }
        fileRecordRepository.saveAll(fileRecords);

        job.setStatus(SyncStatus.SUCCESS);
        syncJobRepository.save(job);
        return DatabaseWriteResult.success(rowsSaved);
    }

    private DatabaseWriteResult writeSchedulePermit(ProcessingContext context) {
        Long syncJobId = context.getEvent().getSyncJobId();
        SyncJob job = syncJobRepository.findById(syncJobId)
                .orElseThrow(() -> new IllegalStateException("Sync job not found: " + syncJobId));
        PermitImportOutcome outcome = permitImportCoordinator.importPermit(context);
        boolean duplicate = outcome.status() == vatm.aerosync.common.enums.PermitImportStatus.DUPLICATE;
        SyncStatus syncStatus = duplicate ? SyncStatus.SKIPPED : SyncStatus.SUCCESS;
        transactionTemplate.executeWithoutResult(status -> {
            FileProcessingStatus fileStatus = duplicate
                    ? FileProcessingStatus.SKIPPED
                    : FileProcessingStatus.SAVED;
            LocalDateTime savedAt = LocalDateTime.now();
            List<FileRecord> fileRecords = fileRecordRepository.findBySyncJobId(syncJobId);
            for (FileRecord record : fileRecords) {
                record.setProcessingStatus(fileStatus);
                record.setRowsSaved(outcome.detailCount());
                record.setDatabaseSavedAt(savedAt);
                record.setErrorMessage(null);
            }
            fileRecordRepository.saveAll(fileRecords);
            job.setStatus(syncStatus);
            syncJobRepository.save(job);
        });
        String message = duplicate
                ? "Permit already exists; target write skipped"
                : "ATFM permit saved (masterId=%d, permId=%d)".formatted(
                        outcome.targetMasterId(), outcome.targetPermId());
        return new DatabaseWriteResult(syncStatus, outcome.detailCount(), message);
    }

    private FlightData toEntity(Long syncJobId, FlightRow row) {
        FlightData entity = new FlightData();
        entity.setSyncJobId(syncJobId);
        entity.setCallsign(row.getCallsign());
        entity.setFromAirport(row.getFrom());
        entity.setToAirport(row.getTo());
        entity.setDateFlight(row.getDateFlight());
        return entity;
    }
}
