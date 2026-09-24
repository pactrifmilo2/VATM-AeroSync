package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
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
import vatm.aerosync.worker.service.VietnameseStoredErrorMessage;
import vatm.aerosync.common.exception.BusinessRuleException;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class DatabaseWriterStep {

    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    // Training/flight_data persistence is intentionally disabled in production.
    // The provider remains only for the legacy test profile; no worker entity or
    // repository package is scanned by AerosyncWorkerApplication.
    private final ObjectProvider<FlightDataRepository> trainingRepository;
    private final PermitImportCoordinator permitImportCoordinator;
    private final TransactionTemplate transactionTemplate;

    public DatabaseWriterStep(SyncJobRepository syncJobRepository,
                              FileRecordRepository fileRecordRepository,
                              ObjectProvider<FlightDataRepository> trainingRepository,
                              PermitImportCoordinator permitImportCoordinator,
                              PlatformTransactionManager transactionManager) {
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.trainingRepository = trainingRepository;
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
        FlightDataRepository flightDataRepository = trainingRepository.getIfAvailable();
        if (flightDataRepository == null) {
            throw new IllegalStateException(
                    "Training flight_data persistence is disabled; only Word permit files are supported");
        }
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

        FlightDataRepository flightDataRepository = trainingRepository.getIfAvailable();
        if (flightDataRepository == null) {
            throw new IllegalStateException("Training flight_data persistence is disabled");
        }
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
        var permit = context.getSchedulePermit();
        var filtered = LandingFlightFilter.filter(permit);
        if (permit.replacesNonScheduledRows() && filtered.flights().size() != permit.flights().size()) {
            throw new vatm.aerosync.common.exception.BusinessRuleException("BR-REVISION-PARTIAL-LD-FILTER",
                    "PERMNO replacement would omit NEW legs; review OLD/NEW scope before deleting rows");
        }
        permit = filtered;
        context.setSchedulePermit(permit);
        if (context.getReceivedDate() != null) {
            permit = permit.withReference("EM " + context.getReceivedDate().format(
                    java.time.format.DateTimeFormatter.ofPattern("ddMMyyyy")));
            context.setSchedulePermit(permit);
        }
        if (!permit.revision() && !permit.importRevision() && !permit.implicitTableRevision()
                && !permit.referencedSeasonNewPermit()) {
            context.setSchedulePermit(permit.withFlights(permit.flights().stream()
                    .map(flight -> flight.withRemark(PermitRemarkPolicy.newFlight(flight)))
                    .toList()));
        } else if (permit.importRevision() && context.getReceivedDate() != null) {
            final var revision = permit;
            final var date = context.getReceivedDate();
            if (!permit.seasonalPermit() && !permit.revisionCreatesNewPermit()) {
                context.setSchedulePermit(permit.withFlights(permit.flights().stream()
                        .map(flight -> flight.withRemark(PermitRemarkPolicy.suddenRevision(
                                flight, date, revision.revisionNumber())))
                        .toList()));
            } else {
                var oldRows = permit.originalFlights();
                if (permit.flights().size() >= oldRows.size()) {
                    var revised = new java.util.ArrayList<vatm.aerosync.worker.model.ScheduleFlight>();
                    for (int index = 0; index < oldRows.size(); index++) {
                        var flight = permit.flights().get(index);
                        revised.add(flight.withRemark(PermitRemarkPolicy.seasonalOld(flight,
                                revision.revisionBasePermitId())));
                    }
                    for (int index = oldRows.size(); index < permit.flights().size(); index++) {
                        var flight = permit.flights().get(index);
                        revised.add(flight.withRemark(PermitRemarkPolicy.seasonalNew(flight,
                                revision.atfmTargetPermitId(), revision.revisionBasePermitId())));
                    }
                    context.setSchedulePermit(permit.withFlights(revised));
                }
            }
        }
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
                record.setErrorMessage(duplicate && !context.getSchedulePermit().revision()
                        ? "Ph\u00e9p n\u00e0y \u0111\u00e3 t\u1ed3n t\u1ea1i r\u1ed3i."
                        : warningMessage(context));
            }
            fileRecordRepository.saveAll(fileRecords);
            job.setStatus(syncStatus);
            syncJobRepository.save(job);
        });
        String message = duplicate
                ? (!context.getSchedulePermit().revision()
                    ? "Ph\u00e9p n\u00e0y \u0111\u00e3 t\u1ed3n t\u1ea1i r\u1ed3i."
                    : "Permit already exists; target write skipped")
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

    static String newPermitRemark(String aircraft) {
        String normalized = AircraftTypeNotation.normalize(aircraft);
        if (normalized == null || normalized.isBlank()) return null;
        var types = java.util.Arrays.stream(normalized.split("[/,;\\s]+"))
                .filter(type -> !type.isBlank()).distinct().toList();
        return types.size() > 1 ? String.join("/", types) : null;
    }

    private String warningMessage(ProcessingContext context) {
        if (context.getSchedulePermit() == null || context.getRowValidationErrors().isEmpty()) {
            return null;
        }
        String warning = context.getRowValidationErrors().stream()
                .map(error -> VietnameseStoredErrorMessage.translate(
                        "Schedule row " + error.rowNumber() + ": " + error.message()))
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse(null);
        return warning == null || warning.length() <= 2000
                ? warning
                : warning.substring(0, 2000);
    }
}
