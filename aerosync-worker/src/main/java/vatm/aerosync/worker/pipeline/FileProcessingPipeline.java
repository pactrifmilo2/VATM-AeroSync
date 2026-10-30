package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.dto.RowValidationError;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.AlertLevel;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.atfm.AtfmReferenceDataException;
import vatm.aerosync.worker.service.AuditLogService;
import vatm.aerosync.worker.service.VietnameseStoredErrorMessage;
import vatm.aerosync.worker.service.SyncResultPublisher;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class FileProcessingPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileProcessingPipeline.class);

    private final SyncJobRepository syncJobRepository;
    private final EmailMetadataRepository emailMetadataRepository;
    private final FileRecordRepository fileRecordRepository;
    private final FormatValidatorStep formatValidatorStep;
    private final ParserStep parserStep;
    private final NormalizerStep normalizerStep;
    private final PurposeResolutionStep purposeResolutionStep;
    private final RevisionReconciliationStep revisionReconciliationStep;
    private final AircraftTypeResolutionStep aircraftTypeResolutionStep;
    private final ViaResolutionStep viaResolutionStep;
    private final BusinessRuleValidatorStep businessRuleValidatorStep;
    private final DatabaseWriterStep databaseWriterStep;
    private final FileArchiverStep fileArchiverStep;
    private final AuditLogService auditLogService;
    private final SyncResultPublisher syncResultPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public FileProcessingPipeline(SyncJobRepository syncJobRepository,
                                  EmailMetadataRepository emailMetadataRepository,
                                  FileRecordRepository fileRecordRepository,
                                  FormatValidatorStep formatValidatorStep,
                                  ParserStep parserStep,
                                  NormalizerStep normalizerStep,
                                  PurposeResolutionStep purposeResolutionStep,
                                  RevisionReconciliationStep revisionReconciliationStep,
                                  AircraftTypeResolutionStep aircraftTypeResolutionStep,
                                  ViaResolutionStep viaResolutionStep,
                                  BusinessRuleValidatorStep businessRuleValidatorStep,
                                  DatabaseWriterStep databaseWriterStep,
                                  FileArchiverStep fileArchiverStep,
                                  AuditLogService auditLogService,
                                  SyncResultPublisher syncResultPublisher) {
        this.syncJobRepository = syncJobRepository;
        this.emailMetadataRepository = emailMetadataRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.formatValidatorStep = formatValidatorStep;
        this.parserStep = parserStep;
        this.normalizerStep = normalizerStep;
        this.purposeResolutionStep = purposeResolutionStep;
        this.revisionReconciliationStep = revisionReconciliationStep;
        this.aircraftTypeResolutionStep = aircraftTypeResolutionStep;
        this.viaResolutionStep = viaResolutionStep;
        this.businessRuleValidatorStep = businessRuleValidatorStep;
        this.databaseWriterStep = databaseWriterStep;
        this.fileArchiverStep = fileArchiverStep;
        this.auditLogService = auditLogService;
        this.syncResultPublisher = syncResultPublisher;
    }

    public void process(FileIngestedEvent event) {
        ProcessingContext context = new ProcessingContext(event);
        context.setFilePath(resolveProcessingPath(event));

        lookupSender(event.getSyncJobId(), context);

        markInProgress(event.getSyncJobId());
        DebugSessionLog.log("E", "FileProcessingPipeline.java:process", "processing started",
                DebugSessionLog.map("syncJobId", event.getSyncJobId(), "path", event.getTempFilePath()));

        try {
            formatValidatorStep.validate(context);
            parserStep.parse(context);
            applySubjectDirective(context);
            normalizerStep.normalize(context);
            purposeResolutionStep.resolve(context);
            revisionReconciliationStep.reconcile(context);
            if (context.getSchedulePermit() != null && context.getSchedulePermit().replacesNonScheduledRows()) {
                purposeResolutionStep.resolve(context);
            }
            aircraftTypeResolutionStep.resolveAllowingMissingForNonSeasonal(context);
            viaResolutionStep.resolveAllowingMissingForNonSeasonal(context);
            businessRuleValidatorStep.validate(context);
            DatabaseWriteResult writeResult = databaseWriterStep.write(context);
            boolean completed = writeResult.status() == SyncStatus.SUCCESS
                    || writeResult.status() == SyncStatus.SKIPPED;
            Path archived = completed
                    ? archiveSafely(event.getSyncJobId(), () -> fileArchiverStep.archiveProcessed(
                            context.getFilePath(), event.getSourceType(), context.getSender()))
                    : archiveSafely(event.getSyncJobId(), () -> fileArchiverStep.archiveError(
                            context.getFilePath(), event.getSourceType(),
                            writeResult.message(), context.getSender()));
            if (archived != null) {
                markArchived(event.getSyncJobId(), archived.toString());
            } else if (completed) {
                // Database write succeeded, but the source file is still in the
                // staging area. Keep the job's SUCCESS/SKIPPED result while
                // exposing the archival failure as an email error so mailbox
                // acknowledgement cannot claim the message was processed.
                updateEmailProcessingStatus(event.getSyncJobId(), EmailProcessingStatus.FAILED);
            }
            if (archived != null) {
                updateEmailProcessingStatus(
                        event.getSyncJobId(),
                        writeResult.status() == SyncStatus.SKIPPED
                                ? EmailProcessingStatus.SKIPPED
                                : EmailProcessingStatus.SAVED);
            }
            DebugSessionLog.log("E", "FileProcessingPipeline.java:process", "archive attempted",
                    DebugSessionLog.map("syncJobId", event.getSyncJobId(), "archivedPath",
                            archived != null ? archived.toString() : null));

            long duration = context.elapsedMillis();
            auditLogService.record(
                    event.getSyncJobId(),
                    writeResult.status() == SyncStatus.SKIPPED ? "SYNC_DUPLICATE" : "SYNC_SUCCESS",
                    summaryInput(context),
                    writeResult.message() + ", rows=" + writeResult.rowsSaved(),
                    writeResult.status(),
                    duration);
            syncResultPublisher.publish(
                    event.getSyncJobId(), archived == null ? SyncStatus.FAILED : writeResult.status(),
                    archived == null ? AlertLevel.WARNING : AlertLevel.INFO,
                    archived == null ? "Database saved but archive failed; retry archive" : writeResult.message());
            publishImportWarnings(context, event, writeResult, archived);
        } catch (NonPermitDocumentException e) {
            handleNonPermitDocument(context, event, e);
        } catch (FormatValidationException e) {
            handleFormatError(context, event, e);
        } catch (BusinessRuleException e) {
            logAirportValidationFailure(context, event, e);
            handleBusinessRuleError(context, event, e);
        } catch (AtfmReferenceDataException e) {
            logAtfmReferenceFailure(context, event, e);
            handleBusinessRuleError(
                    context,
                    event,
                    new BusinessRuleException("BR-ATFM-REFERENCE", e.getMessage()));
        } catch (RuntimeException e) {
            updateJobStatus(event.getSyncJobId(), SyncStatus.FAILED);
            updateFileProcessingStatus(event.getSyncJobId(), FileProcessingStatus.FAILED, e.getMessage());
            updateEmailProcessingStatus(event.getSyncJobId(), EmailProcessingStatus.FAILED);
            Path archived = archiveSafely(event.getSyncJobId(), () -> fileArchiverStep.archiveError(
                    context.getFilePath(), event.getSourceType(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    context.getSender()));
            if (archived != null) {
                markArchived(event.getSyncJobId(), archived.toString());
            }
            auditLogService.record(
                    event.getSyncJobId(),
                    "SYNC_RUNTIME_ERROR",
                    summaryInput(context),
                    e.getMessage(),
                    SyncStatus.FAILED,
                    context.elapsedMillis());
            syncResultPublisher.publish(
                    event.getSyncJobId(), SyncStatus.FAILED, AlertLevel.WARNING,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void logAirportValidationFailure(ProcessingContext context,
                                             FileIngestedEvent event,
                                             BusinessRuleException exception) {
        List<RowValidationError> airportErrors = exception.getRowErrors().stream()
                .filter(error -> error.code() != null
                        && (error.code().contains("AIRPORT")
                        || error.field() != null && error.field().toLowerCase(Locale.ROOT).contains("airport")))
                .toList();
        if (airportErrors.isEmpty()) {
            return;
        }
        LOGGER.warn(
                "AIRPORT_VALIDATION_FAILURE syncJobId={} file={} permit={} operator={} rule={} errors={}",
                event.getSyncJobId(), context.getOriginalFileName(), permitId(context),
                operator(context), exception.getRuleCode(), airportErrors);
    }

    private void logAtfmReferenceFailure(ProcessingContext context,
                                         FileIngestedEvent event,
                                         AtfmReferenceDataException exception) {
        String detail = exception.getMessage() == null ? "" : exception.getMessage();
        if (!detail.toLowerCase(Locale.ROOT).contains("airport")
                && !detail.toLowerCase(Locale.ROOT).contains("m_aero")) {
            return;
        }
        LOGGER.error(
                "ATFM_AIRPORT_REFERENCE_FAILURE syncJobId={} file={} permit={} operator={} message={} airports={}",
                event.getSyncJobId(), context.getOriginalFileName(), permitId(context),
                operator(context), detail, permitAirports(context));
    }

    private String permitId(ProcessingContext context) {
        return context.getSchedulePermit() == null
                ? "-"
                : context.getSchedulePermit().normalizedPermitId();
    }

    private String operator(ProcessingContext context) {
        return context.getSchedulePermit() == null
                ? "-"
                : context.getSchedulePermit().operatorId();
    }

    private String permitAirports(ProcessingContext context) {
        if (context.getSchedulePermit() == null) {
            return "-";
        }
        return context.getSchedulePermit().flights().stream()
                .flatMap(flight -> java.util.stream.Stream.of(
                        flight.fromAirport(), flight.toAirport()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    /**
     * A permit can be stored even when a soft check is not satisfied; operators
     * still need to see which rows must be corrected in the source document.
     */
    private void publishImportWarnings(ProcessingContext context,
                                       FileIngestedEvent event,
                                       DatabaseWriteResult writeResult,
                                       Path archived) {
        if (context.getWarnings().isEmpty()
                || writeResult.status() == SyncStatus.SKIPPED
                || archived == null) {
            return;
        }
        String rows = context.getWarnings().stream()
                .map(warning -> "dòng %d: %s".formatted(
                        warning.rowNumber(), warning.message()))
                .distinct()
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        String message = ("Permit %s đã được lưu; cảnh báo %d dòng lịch bay: %s")
                .formatted(context.getSchedulePermit().normalizedPermitId(),
                        context.getWarnings().size(), rows);
        auditLogService.record(
                event.getSyncJobId(), "SYNC_IMPORT_WARNING", summaryInput(context), message,
                writeResult.status(), context.elapsedMillis());
        syncResultPublisher.publish(
                event.getSyncJobId(), writeResult.status(), AlertLevel.WARNING, message);
    }

    /**
     * A retry republished after the first attempt was archived carries the old
     * staging path in the event. Prefer the path recorded for the job so a
     * quarantined or failed document can be reprocessed from where it now
     * lives instead of failing repeatedly with a missing-file error.
     */
    private Path resolveProcessingPath(FileIngestedEvent event) {
        Path eventPath = Path.of(event.getTempFilePath());
        if (java.nio.file.Files.isRegularFile(eventPath)) {
            return eventPath;
        }
        return fileRecordRepository.findBySyncJobId(event.getSyncJobId()).stream()
                .map(FileRecord::getStoredPath)
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of)
                .filter(java.nio.file.Files::isRegularFile)
                .max(Comparator.comparing(path -> path.toFile().lastModified()))
                .orElse(eventPath);
    }

    private void applySubjectDirective(ProcessingContext context) {
        String subject = context.getEvent().getSubject();
        if (subject == null || subject.isBlank() || context.getSchedulePermit() == null) {
            return;
        }
        if (java.util.regex.Pattern.compile("(?iu)\\b(?:CORRECT|UPDATE|CORR)\\b")
                .matcher(subject).find()) {
            context.setSchedulePermit(context.getSchedulePermit()
                    .withWorkflow(SchedulePermit.SUBJECT_CORRECTION));
        }
    }

    private void lookupSender(Long syncJobId, ProcessingContext context) {
        emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(syncJobId)
                .ifPresent(metadata -> {
                    context.setSender(metadata.getSender());
                    if (metadata.getReceivedAt() != null) {
                        context.setReceivedDate(metadata.getReceivedAt().toLocalDate());
                    }
                });
        if (context.getReceivedDate() == null) {
            fileRecordRepository.findBySyncJobId(syncJobId).stream()
                    .map(vatm.aerosync.common.entity.FileRecord::getCreatedAt)
                    .filter(java.util.Objects::nonNull)
                    .min(java.time.LocalDateTime::compareTo)
                    .ifPresent(date -> context.setReceivedDate(date.toLocalDate()));
        }
    }

    private void markArchived(Long syncJobId, String storedPath) {
        fileRecordRepository.findBySyncJobId(syncJobId).forEach(record -> {
            record.setStoredPath(storedPath);
            record.setArchiveStatus(FileArchiveStatus.ARCHIVED);
            record.setArchivedAt(LocalDateTime.now());
            fileRecordRepository.save(record);
        });
    }

    private void markInProgress(Long syncJobId) {
        syncJobRepository.findById(syncJobId).ifPresent(job -> {
            job.setStatus(SyncStatus.IN_PROGRESS);
            syncJobRepository.save(job);
        });
        updateFileProcessingStatus(syncJobId, FileProcessingStatus.PROCESSING, null);
        updateEmailProcessingStatus(syncJobId, EmailProcessingStatus.PROCESSING);
    }

    private void handleFormatError(ProcessingContext context, FileIngestedEvent event, FormatValidationException e) {
        updateJobStatus(event.getSyncJobId(), SyncStatus.FAILED);
        updateFileProcessingStatus(event.getSyncJobId(), FileProcessingStatus.FAILED, e.getErrorDetail());
        updateEmailProcessingStatus(event.getSyncJobId(), EmailProcessingStatus.FAILED);
        Path archived = archiveSafely(event.getSyncJobId(), () -> fileArchiverStep.archiveFormatError(
                context.getFilePath(), event.getSourceType(), e.getErrorDetail(), context.getSender()));
        if (archived != null) {
            markArchived(event.getSyncJobId(), archived.toString());
        }
        auditLogService.record(
                event.getSyncJobId(),
                "SYNC_FORMAT_ERROR",
                summaryInput(context),
                e.getErrorDetail(),
                SyncStatus.FAILED,
                context.elapsedMillis());
        syncResultPublisher.publish(
                event.getSyncJobId(), SyncStatus.FAILED, AlertLevel.WARNING, e.getMessage());
    }

    private void handleNonPermitDocument(ProcessingContext context,
                                         FileIngestedEvent event,
                                         NonPermitDocumentException e) {
        updateJobStatus(event.getSyncJobId(), SyncStatus.SKIPPED);
        updateFileProcessingStatus(event.getSyncJobId(), FileProcessingStatus.SKIPPED, e.getMessage());
        updateEmailProcessingStatus(event.getSyncJobId(), EmailProcessingStatus.SKIPPED);
        Path archived = archiveSafely(event.getSyncJobId(), () -> fileArchiverStep.archiveProcessed(
                context.getFilePath(), event.getSourceType(), context.getSender()));
        if (archived != null) {
            markArchived(event.getSyncJobId(), archived.toString());
        }
        auditLogService.record(
                event.getSyncJobId(),
                "SYNC_NON_PERMIT_ATTACHMENT",
                summaryInput(context),
                e.getMessage(),
                SyncStatus.SKIPPED,
                context.elapsedMillis());
        syncResultPublisher.publish(
                event.getSyncJobId(), SyncStatus.SKIPPED, AlertLevel.INFO, e.getMessage());
    }

    @Transactional
    void handleBusinessRuleError(ProcessingContext context, FileIngestedEvent event, BusinessRuleException e) {
        updateJobStatus(event.getSyncJobId(), SyncStatus.QUARANTINED);
        updateFileProcessingStatus(event.getSyncJobId(), FileProcessingStatus.QUARANTINED, e.getMessage());
        updateEmailProcessingStatus(event.getSyncJobId(), EmailProcessingStatus.QUARANTINED);
        Path archived = archiveSafely(event.getSyncJobId(), () -> fileArchiverStep.archiveQuarantine(
                context.getFilePath(), event.getSourceType(), context.getSender()));
        if (archived != null) {
            markArchived(event.getSyncJobId(), archived.toString());
        }
        auditLogService.record(
                event.getSyncJobId(),
                "SYNC_QUARANTINE",
                summaryInput(context),
                businessRuleOutput(e),
                SyncStatus.QUARANTINED,
                context.elapsedMillis());
        syncResultPublisher.publish(
                event.getSyncJobId(), SyncStatus.QUARANTINED, AlertLevel.WARNING, e.getMessage());
    }

    private void updateJobStatus(Long syncJobId, SyncStatus status) {
        syncJobRepository.findById(syncJobId).ifPresent(job -> {
            job.setStatus(status);
            syncJobRepository.save(job);
        });
    }

    private void updateFileProcessingStatus(Long syncJobId,
                                            FileProcessingStatus status,
                                            String errorMessage) {
        fileRecordRepository.findBySyncJobId(syncJobId).forEach(record -> {
            record.setProcessingStatus(status);
            record.setErrorMessage(truncateError(VietnameseStoredErrorMessage.translate(errorMessage)));
            fileRecordRepository.save(record);
        });
    }

    private void markArchiveFailed(Long syncJobId, Exception exception) {
        fileRecordRepository.findBySyncJobId(syncJobId).forEach(record -> {
            record.setArchiveStatus(FileArchiveStatus.FAILED);
            record.setErrorMessage(truncateError(VietnameseStoredErrorMessage.translate(
                    "Archive failed: " + exception.getMessage())));
            fileRecordRepository.save(record);
        });
    }

    private void updateEmailProcessingStatus(Long syncJobId, EmailProcessingStatus status) {
        emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(syncJobId).ifPresent(metadata -> {
            metadata.setProcessingStatus(status);
            emailMetadataRepository.save(metadata);
        });
    }

    private String truncateError(String message) {
        return VietnameseStoredErrorMessage.fitErrorMessageColumn(message);
    }

    private String summaryInput(ProcessingContext context) {
        String sender = context.getSender();
        return "file=" + context.getOriginalFileName()
                + ",type=" + context.getFileType()
                + (sender != null ? ",sender=" + sender : "");
    }

    private String businessRuleOutput(BusinessRuleException e) {
        try {
            return objectMapper.writeValueAsString(new BusinessRuleOutput(e.getMessage(), e.getRowErrors()));
        } catch (JsonProcessingException jsonException) {
            return e.getRuleCode() + ": " + e.getMessage();
        }
    }

    private Path archiveSafely(Long syncJobId, ArchiveAction action) {
        try {
            return action.run();
        } catch (Exception e) {
            markArchiveFailed(syncJobId, e);
            DebugSessionLog.log("E", "FileProcessingPipeline.java:archiveSafely", "archive failed",
                    DebugSessionLog.map("error", e.getClass().getSimpleName(), "message", e.getMessage()));
            return null;
        }
    }

    @FunctionalInterface
    private interface ArchiveAction {
        Path run() throws Exception;
    }

    private record BusinessRuleOutput(String message, Object rowErrors) {
    }
}
