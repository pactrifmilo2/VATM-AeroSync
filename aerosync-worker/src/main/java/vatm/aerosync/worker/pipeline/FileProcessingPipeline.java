package vatm.aerosync.worker.pipeline;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.AlertLevel;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.common.exception.FormatValidationException;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.service.AuditLogService;
import vatm.aerosync.worker.service.SyncResultPublisher;

import java.nio.file.Path;

@Service
public class FileProcessingPipeline {

    private final SyncJobRepository syncJobRepository;
    private final FormatValidatorStep formatValidatorStep;
    private final ParserStep parserStep;
    private final NormalizerStep normalizerStep;
    private final BusinessRuleValidatorStep businessRuleValidatorStep;
    private final DatabaseWriterStep databaseWriterStep;
    private final FileArchiverStep fileArchiverStep;
    private final AuditLogService auditLogService;
    private final SyncResultPublisher syncResultPublisher;

    public FileProcessingPipeline(SyncJobRepository syncJobRepository,
                                  FormatValidatorStep formatValidatorStep,
                                  ParserStep parserStep,
                                  NormalizerStep normalizerStep,
                                  BusinessRuleValidatorStep businessRuleValidatorStep,
                                  DatabaseWriterStep databaseWriterStep,
                                  FileArchiverStep fileArchiverStep,
                                  AuditLogService auditLogService,
                                  SyncResultPublisher syncResultPublisher) {
        this.syncJobRepository = syncJobRepository;
        this.formatValidatorStep = formatValidatorStep;
        this.parserStep = parserStep;
        this.normalizerStep = normalizerStep;
        this.businessRuleValidatorStep = businessRuleValidatorStep;
        this.databaseWriterStep = databaseWriterStep;
        this.fileArchiverStep = fileArchiverStep;
        this.auditLogService = auditLogService;
        this.syncResultPublisher = syncResultPublisher;
    }

    public void process(FileIngestedEvent event) {
        ProcessingContext context = new ProcessingContext(event);
        context.setFilePath(Path.of(event.getTempFilePath()));

        markInProgress(event.getSyncJobId());
        DebugSessionLog.log("E", "FileProcessingPipeline.java:process", "processing started",
                DebugSessionLog.map("syncJobId", event.getSyncJobId(), "path", event.getTempFilePath()));

        try {
            formatValidatorStep.validate(context);
            parserStep.parse(context);
            normalizerStep.normalize(context);
            businessRuleValidatorStep.validate(context);
            databaseWriterStep.write(context);
            Path archived = archiveSafely(() -> fileArchiverStep.archiveProcessed(context.getFilePath(), event.getSourceType()));
            DebugSessionLog.log("E", "FileProcessingPipeline.java:process", "archive attempted",
                    DebugSessionLog.map("syncJobId", event.getSyncJobId(), "archivedPath",
                            archived != null ? archived.toString() : null));

            long duration = context.elapsedMillis();
            auditLogService.record(
                    event.getSyncJobId(),
                    "SYNC_SUCCESS",
                    summaryInput(context),
                    "rows=" + context.getRows().size(),
                    SyncStatus.SUCCESS,
                    duration);
            syncResultPublisher.publish(
                    event.getSyncJobId(), SyncStatus.SUCCESS, AlertLevel.INFO, "Sync completed successfully");
        } catch (FormatValidationException e) {
            handleFormatError(context, event, e);
        } catch (BusinessRuleException e) {
            handleBusinessRuleError(context, event, e);
        }
    }

    private void markInProgress(Long syncJobId) {
        syncJobRepository.findById(syncJobId).ifPresent(job -> {
            job.setStatus(SyncStatus.IN_PROGRESS);
            syncJobRepository.save(job);
        });
    }

    private void handleFormatError(ProcessingContext context, FileIngestedEvent event, FormatValidationException e) {
        updateJobStatus(event.getSyncJobId(), SyncStatus.FAILED);
        archiveSafely(() -> fileArchiverStep.archiveError(
                context.getFilePath(), event.getSourceType(), e.getErrorDetail()));
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
        updateJobStatus(event.getSyncJobId(), SyncStatus.QUARANTINED);
        archiveSafely(() -> fileArchiverStep.archiveQuarantine(context.getFilePath(), event.getSourceType()));
        auditLogService.record(
                event.getSyncJobId(),
                "SYNC_QUARANTINE",
                summaryInput(context),
                e.getRuleCode() + ": " + e.getMessage(),
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

    private String summaryInput(ProcessingContext context) {
        return "file=" + context.getOriginalFileName() + ",type=" + context.getFileType();
    }

    private Path archiveSafely(ArchiveAction action) {
        try {
            return action.run();
        } catch (Exception e) {
            DebugSessionLog.log("E", "FileProcessingPipeline.java:archiveSafely", "archive failed",
                    DebugSessionLog.map("error", e.getClass().getSimpleName(), "message", e.getMessage()));
            return null;
        }
    }

    @FunctionalInterface
    private interface ArchiveAction {
        Path run() throws Exception;
    }
}
