package vatm.aerosync.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.atfm.AtfmPermitSnapshot;
import vatm.aerosync.worker.atfm.AtfmRevisionBaseline;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.atfm.AtfmWriteResult;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class PermitImportCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermitImportCoordinator.class);
    private static final ZoneId AUDIT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    static final String LOCK_PREFIX = "aerosync:permit-lock:";

    private final PermitImportRepository permitImportRepository;
    private final SyncJobRepository syncJobRepository;
    private final PermitSemanticHasher semanticHasher;
    private final AtfmScheduleGateway atfmScheduleGateway;
    private final AtfmDatabaseProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final AuditLogService auditLogService;

    public PermitImportCoordinator(PermitImportRepository permitImportRepository,
                                   SyncJobRepository syncJobRepository,
                                   PermitSemanticHasher semanticHasher,
                                   AtfmScheduleGateway atfmScheduleGateway,
                                   AtfmDatabaseProperties properties,
                                   StringRedisTemplate redisTemplate,
                                   AuditLogService auditLogService) {
        this.permitImportRepository = permitImportRepository;
        this.syncJobRepository = syncJobRepository;
        this.semanticHasher = semanticHasher;
        this.atfmScheduleGateway = atfmScheduleGateway;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.auditLogService = auditLogService;
    }

    public PermitImportOutcome importPermit(ProcessingContext context) {
        SchedulePermit permit = context.getSchedulePermit();
        Long syncJobId = context.getEvent().getSyncJobId();
        SyncJob job = syncJobRepository.findById(syncJobId)
                .orElseThrow(() -> new IllegalStateException("Sync job not found: " + syncJobId));
        String semanticHash = semanticHasher.hash(permit);
        Optional<PermitImport> existingAttempt = permitImportRepository.findBySyncJobId(syncJobId);
        boolean retryFailed = existingAttempt
                .map(previous -> previous.getStatus() == PermitImportStatus.FAILED).orElse(false);
        PermitImport attempt = existingAttempt.orElseGet(() -> reserve(job, permit, semanticHash));

        if (attempt.getStatus() == PermitImportStatus.SAVED
                || attempt.getStatus() == PermitImportStatus.DUPLICATE) {
            return outcome(attempt);
        }
        if (existingAttempt.isPresent()) {
            refreshAttempt(attempt, job, permit, semanticHash);
        }

        String targetPermitId = permit.atfmTargetPermitId();
        String lockKey = LOCK_PREFIX + targetPermitId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey, Long.toString(syncJobId), Duration.ofSeconds(properties.getPermitLockSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("Another worker is processing permit " + targetPermitId);
        }

        try {
            if (permit.importRevision()) {
                return importRevision(syncJobId, attempt, permit);
            }

            if (!properties.isWriteEnabled()) {
                attempt.setStatus(PermitImportStatus.DRY_RUN);
                attempt.setDetailCount(permit.flights().size());
                attempt.setErrorMessage(VietnameseStoredErrorMessage.translate("ATFM writes are disabled"));
                permitImportRepository.save(attempt);
                throw new BusinessRuleException(
                        "ATFM-WRITE-DISABLED",
                        "Permit parsed and validated, but ATFM writes are disabled");
            }

            if (permit.subjectCorrection()) {
                Optional<AtfmPermitSnapshot> correctionTarget = atfmScheduleGateway.findExisting(permit);
                if (correctionTarget.isEmpty()) {
                    markRevision(attempt, "Subject correction target master was not found");
                    throw new BusinessRuleException(
                            "BR-SUBJECT-CORRECTION-BASE-NOT-FOUND",
                            "Subject correction master not found: " + permit.atfmTargetPermitId());
                }
                return updateExistingPermit(syncJobId, attempt, permit, correctionTarget.get());
            }

            if (!permit.revision() && !permit.implicitTableRevision()
                    && !permit.referencedSeasonNewPermit() && !retryFailed
                    && context.getRowValidationErrors().isEmpty()) {
                Optional<AtfmPermitSnapshot> duplicate =
                        atfmScheduleGateway.findNewPermitInEitherStore(permit);
                if (duplicate.isPresent()) {
                    AtfmPermitSnapshot snapshot = duplicate.get();
                    markDuplicate(attempt, snapshot.masterId(), snapshot.permId(), 0);
                    attempt.setErrorMessage("Ph\u00e9p n\u00e0y \u0111\u00e3 t\u1ed3n t\u1ea1i r\u1ed3i.");
                    permitImportRepository.save(attempt);
                    return outcome(attempt);
                }
                return insertPermit(syncJobId, attempt, permit);
            }

            Optional<AtfmPermitSnapshot> target = atfmScheduleGateway.findExisting(permit);
            if (target.isPresent()) {
                AtfmPermitSnapshot snapshot = target.get();
                if (snapshot.matchesExpectedPermit()) {
                    markDuplicate(attempt, snapshot.masterId(), snapshot.permId(), permit.flights().size());
                    return outcome(attempt);
                }
                return updateExistingPermit(syncJobId, attempt, permit, snapshot);
            }

            return insertPermit(syncJobId, attempt, permit);
        } catch (BusinessRuleException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            attempt.setStatus(PermitImportStatus.FAILED);
            attempt.setErrorMessage(truncate(VietnameseStoredErrorMessage.translate(exception.getMessage())));
            permitImportRepository.save(attempt);
            throw exception;
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private PermitImportOutcome updateExistingPermit(Long syncJobId,
                                                      PermitImport attempt,
                                                      SchedulePermit permit) {
        return updateExistingPermit(syncJobId, attempt, permit, null);
    }

    private PermitImportOutcome updateExistingPermit(Long syncJobId,
                                                      PermitImport attempt,
                                                      SchedulePermit permit,
                                                      AtfmPermitSnapshot knownTarget) {
        if (!properties.isWriteEnabled()) {
            attempt.setStatus(PermitImportStatus.DRY_RUN);
            attempt.setDetailCount(permit.flights().size());
            attempt.setErrorMessage(VietnameseStoredErrorMessage.translate("ATFM writes are disabled"));
            permitImportRepository.save(attempt);
            throw new BusinessRuleException(
                    "ATFM-WRITE-DISABLED",
                    "Revision parsed, but ATFM writes are disabled");
        }

        Optional<AtfmPermitSnapshot> target = knownTarget != null
                ? Optional.of(knownTarget)
                : atfmScheduleGateway.findExisting(permit);
        if (target.isEmpty()) {
            Optional<AtfmRevisionBaseline> crossStore =
                    atfmScheduleGateway.findCrossStoreRevisionBaseline(permit);
            if (crossStore.isPresent()) {
                markRevision(attempt,
                        "Original permit %s is stored in the non-scheduled (NO) table, but this revision "
                                + "has a multi-day flight and must be stored in the seasonal (SC) table; "
                                + "manual handling is required to move the data across storage tables."
                                        .formatted(permit.atfmTargetPermitId()));
                throw new BusinessRuleException(
                        "BR-REVISION-BASE-WRONG-STORE",
                        "Original ATFM permit found in a different storage table than the revision "
                                + "requires: " + permit.atfmTargetPermitId());
            }
            markRevision(attempt, "Original ATFM permit was not found");
            throw new BusinessRuleException(
                    "BR-REVISION-BASE-NOT-FOUND",
                    "Original ATFM permit not found: " + permit.atfmTargetPermitId());
        }
        AtfmPermitSnapshot snapshot = target.get();
        if (snapshot.matchesExpectedPermit()) {
            markDuplicate(attempt, snapshot.masterId(), snapshot.permId(), permit.flights().size());
            return outcome(attempt);
        }

        AtfmWriteResult result = writeAtfm(syncJobId, permit, "UPDATE",
                () -> atfmScheduleGateway.update(permit));
        if (result.detailCount() == 0) {
            markDuplicate(attempt, result.masterId(), result.permId(), permit.flights().size());
            return outcome(attempt);
        }
        attempt.setStatus(PermitImportStatus.SAVED);
        attempt.setTargetMasterId(result.masterId());
        attempt.setTargetPermId(result.permId());
        attempt.setDetailCount(result.detailCount());
        attempt.setErrorMessage(null);
        permitImportRepository.save(attempt);
        return outcome(attempt);
    }

    /** Revision path after the parser has selected replacement/new rows. */
    private PermitImportOutcome importRevision(Long syncJobId,
                                                PermitImport attempt,
                                                SchedulePermit permit) {
        if (!properties.isWriteEnabled()) {
            attempt.setStatus(PermitImportStatus.DRY_RUN);
            attempt.setDetailCount(permit.flights().size());
            attempt.setErrorMessage(VietnameseStoredErrorMessage.translate("ATFM writes are disabled"));
            permitImportRepository.save(attempt);
            throw new BusinessRuleException(
                    "ATFM-WRITE-DISABLED",
                    "Revision parsed, but ATFM writes are disabled");
        }

        if (!permit.seasonalPermit()) {
            // Sudden/non-scheduled revisions update the original NO permit.
            // Only seasonal revisions may create a separate R(n) permit.
            return updateExistingPermit(syncJobId, attempt, permit);
        }

        Optional<AtfmPermitSnapshot> target = atfmScheduleGateway.findExisting(permit);
        if (target.isEmpty()) {
            return insertPermit(syncJobId, attempt, permit);
        }
        AtfmPermitSnapshot snapshot = target.get();
        if (snapshot.matchesExpectedPermit()) {
            markDuplicate(attempt, snapshot.masterId(), snapshot.permId(), permit.flights().size());
            return outcome(attempt);
        }
        return updateExistingPermit(syncJobId, attempt, permit, snapshot);
    }

    private PermitImportOutcome insertPermit(Long syncJobId,
                                             PermitImport attempt,
                                             SchedulePermit permit) {
        AtfmWriteResult result = writeAtfm(syncJobId, permit, "INSERT",
                () -> atfmScheduleGateway.insert(permit));
        attempt.setStatus(PermitImportStatus.SAVED);
        attempt.setTargetMasterId(result.masterId());
        attempt.setTargetPermId(result.permId());
        attempt.setDetailCount(result.detailCount());
        attempt.setErrorMessage(null);
        permitImportRepository.save(attempt);
        return outcome(attempt);
    }

    /**
     * Persists a best-effort audit record around each transaction that reaches
     * the ATFM JDBC write gateway. The audit database is not authoritative for
     * ATFM: an audit persistence failure must never turn a committed ATFM write
     * into an application failure (or mask the original ATFM exception).
     */
    private AtfmWriteResult writeAtfm(Long syncJobId,
                                      SchedulePermit permit,
                                      String operation,
                                      AtfmWriteAction action) {
        OffsetDateTime startedAt = OffsetDateTime.now(AUDIT_ZONE);
        String tables = storageTables(permit);
        String input = "operation=" + operation
                + "; permit=" + permit.atfmTargetPermitId()
                + "; tables=" + tables
                + "; startedAt=" + AUDIT_TIME.format(startedAt);
        try {
            AtfmWriteResult result = action.execute();
            OffsetDateTime finishedAt = OffsetDateTime.now(AUDIT_ZONE);
            long durationMs = durationMs(startedAt, finishedAt);
            recordAtfmAudit(syncJobId, "ATFM_" + operation, input,
                    "result=SUCCESS"
                            + "; operation=" + operation
                            + "; permit=" + permit.atfmTargetPermitId()
                            + "; tables=" + tables
                            + "; startedAt=" + AUDIT_TIME.format(startedAt)
                            + "; finishedAt=" + AUDIT_TIME.format(finishedAt)
                            + "; durationMs=" + durationMs
                            + "; masterId=" + result.masterId()
                            + "; permId=" + result.permId()
                            + "; detailCount=" + result.detailCount(),
                    SyncStatus.SUCCESS, durationMs);
            return result;
        } catch (RuntimeException exception) {
            OffsetDateTime finishedAt = OffsetDateTime.now(AUDIT_ZONE);
            long durationMs = durationMs(startedAt, finishedAt);
            recordAtfmAudit(syncJobId, "ATFM_" + operation + "_FAILED", input,
                    "result=FAILED"
                            + "; operation=" + operation
                            + "; permit=" + permit.atfmTargetPermitId()
                            + "; tables=" + tables
                            + "; startedAt=" + AUDIT_TIME.format(startedAt)
                            + "; finishedAt=" + AUDIT_TIME.format(finishedAt)
                            + "; durationMs=" + durationMs
                            + "; errorType=" + exception.getClass().getName()
                            + "; error=" + exceptionDetails(exception),
                    SyncStatus.FAILED, durationMs);
            throw exception;
        }
    }

    @FunctionalInterface
    private interface AtfmWriteAction {
        AtfmWriteResult execute();
    }

    private void recordAtfmAudit(Long syncJobId,
                                 String action,
                                 String input,
                                 String output,
                                 SyncStatus status,
                                 long durationMs) {
        try {
            auditLogService.record(syncJobId, action, input, output, status, durationMs);
        } catch (RuntimeException auditException) {
            LOGGER.error("ATFM audit persistence failed action={} syncJobId={} error={}",
                    action, syncJobId, exceptionDetails(auditException), auditException);
        }
    }

    private String storageTables(SchedulePermit permit) {
        return permit.usesScheduledStorage()
                ? "T_PERMMASTER_SC/T_PERMDETAIL_SC"
                : "T_PERMMASTER_NO/T_PERMDETAIL_NO";
    }

    private long durationMs(OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        return Duration.between(startedAt.toInstant(), finishedAt.toInstant()).toMillis();
    }

    private String exceptionDetails(Throwable exception) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null && !messages.contains(current.getMessage())) {
                messages.add(current.getMessage());
            }
        }
        return messages.isEmpty() ? exception.getClass().getSimpleName() : String.join(" | ", messages);
    }

    private PermitImport reserve(SyncJob job, SchedulePermit permit, String semanticHash) {
        PermitImport attempt = new PermitImport();
        attempt.setSyncJob(job);
        attempt.setNormalizedPermitId(permit.atfmTargetPermitId());
        attempt.setSemanticHash(semanticHash);
        attempt.setSourceFileHash(job.getFileHash());
        attempt.setStatus(PermitImportStatus.RESERVED);
        attempt.setDetailCount(permit.flights().size());
        return permitImportRepository.save(attempt);
    }

    private void refreshAttempt(PermitImport attempt,
                                SyncJob job,
                                SchedulePermit permit,
                                String semanticHash) {
        attempt.setNormalizedPermitId(permit.atfmTargetPermitId());
        attempt.setSemanticHash(semanticHash);
        attempt.setSourceFileHash(job.getFileHash());
        attempt.setDetailCount(permit.flights().size());
        attempt.setErrorMessage(null);
        permitImportRepository.save(attempt);
    }

    private void markDuplicate(PermitImport attempt,
                               Long masterId,
                               Long permId,
                               int detailCount) {
        attempt.setStatus(PermitImportStatus.DUPLICATE);
        attempt.setTargetMasterId(masterId);
        attempt.setTargetPermId(permId);
        attempt.setDetailCount(detailCount);
        attempt.setErrorMessage(null);
        permitImportRepository.save(attempt);
    }

    private void markRevision(PermitImport attempt, String message) {
        attempt.setStatus(PermitImportStatus.REVISION_REVIEW);
        attempt.setErrorMessage(VietnameseStoredErrorMessage.translate(message));
        permitImportRepository.save(attempt);
    }

    private PermitImportOutcome outcome(PermitImport attempt) {
        return new PermitImportOutcome(
                attempt.getStatus(),
                attempt.getDetailCount() == null ? 0 : attempt.getDetailCount(),
                attempt.getTargetMasterId(),
                attempt.getTargetPermId());
    }

    private String truncate(String message) {
        return VietnameseStoredErrorMessage.fitErrorMessageColumn(message);
    }
}
