package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.AlertLevel;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.PermitTrainingSourceState;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.atfm.AtfmReferenceDataException;
import vatm.aerosync.worker.service.AuditLogService;
import vatm.aerosync.worker.service.SyncResultPublisher;

import java.nio.file.Path;
import java.time.LocalDateTime;

@Service
public class FileProcessingPipeline {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(FileProcessingPipeline.class);

    private final SyncJobRepository syncJobRepository;
    private final EmailMetadataRepository emailMetadataRepository;
    private final FileRecordRepository fileRecordRepository;
    private final FormatValidatorStep formatValidatorStep;
    private final ParserStep parserStep;
    private final NormalizerStep normalizerStep;
    private final AircraftTypeResolutionStep aircraftTypeResolutionStep;
    private final ViaResolutionStep viaResolutionStep;
    private final BusinessRuleValidatorStep businessRuleValidatorStep;
    private final DatabaseWriterStep databaseWriterStep;
    private final FileArchiverStep fileArchiverStep;
    private final AuditLogService auditLogService;
    private final SyncResultPublisher syncResultPublisher;
    private final PermitTrainingSourceCaptureService trainingSourceCaptureService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public FileProcessingPipeline(SyncJobRepository syncJobRepository,
                                  EmailMetadataRepository emailMetadataRepository,
                                  FileRecordRepository fileRecordRepository,
                                  FormatValidatorStep formatValidatorStep,
                                  ParserStep parserStep,
                                  NormalizerStep normalizerStep,
                                  AircraftTypeResolutionStep aircraftTypeResolutionStep,
                                  ViaResolutionStep viaResolutionStep,
                                  BusinessRuleValidatorStep businessRuleValidatorStep,
                                  DatabaseWriterStep databaseWriterStep,
                                  FileArchiverStep fileArchiverStep,
                                  AuditLogService auditLogService,
                                  SyncResultPublisher syncResultPublisher,
                                  PermitTrainingSourceCaptureService
                                          trainingSourceCaptureService) {
        this.syncJobRepository = syncJobRepository;
        this.emailMetadataRepository = emailMetadataRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.formatValidatorStep = formatValidatorStep;
        this.parserStep = parserStep;
        this.normalizerStep = normalizerStep;
        this.aircraftTypeResolutionStep = aircraftTypeResolutionStep;
        this.viaResolutionStep = viaResolutionStep;
        this.businessRuleValidatorStep = businessRuleValidatorStep;
        this.databaseWriterStep = databaseWriterStep;
        this.fileArchiverStep = fileArchiverStep;
        this.auditLogService = auditLogService;
        this.syncResultPublisher = syncResultPublisher;
        this.trainingSourceCaptureService = trainingSourceCaptureService;
    }

    public void process(FileIngestedEvent event) {
        ProcessingContext context = new ProcessingContext(event);
        context.setFilePath(Path.of(event.getTempFilePath()));

        lookupSender(event.getSyncJobId(), context);

        markInProgress(event.getSyncJobId());
        DebugSessionLog.log("E", "FileProcessingPipeline.java:process", "processing started",
                DebugSessionLog.map("syncJobId", event.getSyncJobId(), "path", event.getTempFilePath()));

        try {
            formatValidatorStep.validate(context);
            recordTrainingSource(
                    context,
                    PermitTrainingSourceState.PROCESSING,
                    null);
            parserStep.parse(context);
            normalizerStep.normalize(context);
            aircraftTypeResolutionStep.resolve(context);
            viaResolutionStep.resolve(context);
            businessRuleValidatorStep.validate(context);
            DatabaseWriteResult writeResult = databaseWriterStep.write(context);
            recordTrainingSource(
                    context,
                    PermitTrainingSourceState.PARSED,
                    null);
            Path archived = archiveSafely(event.getSyncJobId(), () ->
                    fileArchiverStep.archiveProcessed(context.getFilePath(), event.getSourceType(), context.getSender()));
            if (archived != null) {
                markArchived(event.getSyncJobId(), archived.toString());
            }
            updateEmailProcessingStatus(
                    event.getSyncJobId(),
                    writeResult.status() == SyncStatus.SKIPPED
                            ? EmailProcessingStatus.SKIPPED
                            : EmailProcessingStatus.SAVED);
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
                    event.getSyncJobId(), writeResult.status(), AlertLevel.INFO, writeResult.message());
        } catch (FormatValidationException e) {
            handleFormatError(context, event, e);
        } catch (BusinessRuleException e) {
            handleBusinessRuleError(context, event, e);
        } catch (AtfmReferenceDataException e) {
            handleBusinessRuleError(
                    context,
                    event,
                    new BusinessRuleException("BR-ATFM-REFERENCE", e.getMessage()));
        } catch (RuntimeException e) {
            recordTrainingSource(
                    context,
                    PermitTrainingSourceState.FAILED,
                    e.getMessage());
            updateJobStatus(event.getSyncJobId(), SyncStatus.FAILED);
            updateFileProcessingStatus(event.getSyncJobId(), FileProcessingStatus.FAILED, e.getMessage());
            updateEmailProcessingStatus(event.getSyncJobId(), EmailProcessingStatus.FAILED);
            throw e;
        }
    }

    private void lookupSender(Long syncJobId, ProcessingContext context) {
        emailMetadataRepository.findFirstBySyncJobIdOrderByIdAsc(syncJobId)
                .ifPresent(metadata -> context.setSender(metadata.getSender()));
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
        recordTrainingSource(
                context,
                PermitTrainingSourceState.FAILED,
                e.getErrorDetail());
        updateJobStatus(event.getSyncJobId(), SyncStatus.FAILED);
        updateFileProcessingStatus(event.getSyncJobId(), FileProcessingStatus.FAILED, e.getErrorDetail());
        updateEmailProcessingStatus(event.getSyncJobId(), EmailProcessingStatus.FAILED);
        Path archived = archiveSafely(event.getSyncJobId(), () -> fileArchiverStep.archiveError(
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

    @Transactional
    void handleBusinessRuleError(ProcessingContext context, FileIngestedEvent event, BusinessRuleException e) {
        PermitTrainingSourceState trainingState =
                "PERMIT-REVISION-REVIEW".equals(e.getRuleCode())
                        ? PermitTrainingSourceState.REVIEW_REQUIRED
                        : PermitTrainingSourceState.QUARANTINED;
        recordTrainingSource(context, trainingState, e.getMessage());
        updateJobStatus(event.getSyncJobId(), SyncStatus.QUARANTINED);
        updateFileProcessingStatus(event.getSyncJobId(), FileProcessingStatus.QUARANTINED, e.getMessage());
        updateEmailProcessingStatus(event.getSyncJobId(), EmailProcessingStatus.QUARANTINED);
        Path archived = archiveSafely(event.getSyncJobId(), () ->
                fileArchiverStep.archiveQuarantine(context.getFilePath(), event.getSourceType(), context.getSender()));
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
            record.setErrorMessage(truncateError(errorMessage));
            fileRecordRepository.save(record);
        });
    }

    private void markArchiveFailed(Long syncJobId, Exception exception) {
        fileRecordRepository.findBySyncJobId(syncJobId).forEach(record -> {
            record.setArchiveStatus(FileArchiveStatus.FAILED);
            record.setErrorMessage(truncateError("Archive failed: " + exception.getMessage()));
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
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
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

    private void recordTrainingSource(
            ProcessingContext context,
            PermitTrainingSourceState state,
            String error) {
        try {
            trainingSourceCaptureService.record(context, state, error);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Could not record permit training source for sync job {}",
                    context.getEvent().getSyncJobId(),
                    exception);
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
