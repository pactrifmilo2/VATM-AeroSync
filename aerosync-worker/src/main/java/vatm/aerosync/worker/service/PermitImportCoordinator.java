package vatm.aerosync.worker.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.exception.BusinessRuleException;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.worker.atfm.AtfmPermitSnapshot;
import vatm.aerosync.worker.atfm.AtfmScheduleGateway;
import vatm.aerosync.worker.atfm.AtfmWriteResult;
import vatm.aerosync.worker.config.AtfmDatabaseProperties;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class PermitImportCoordinator {

    static final String LOCK_PREFIX = "aerosync:permit-lock:";

    private final PermitImportRepository permitImportRepository;
    private final SyncJobRepository syncJobRepository;
    private final PermitSemanticHasher semanticHasher;
    private final AtfmScheduleGateway atfmScheduleGateway;
    private final AtfmDatabaseProperties properties;
    private final StringRedisTemplate redisTemplate;

    public PermitImportCoordinator(PermitImportRepository permitImportRepository,
                                   SyncJobRepository syncJobRepository,
                                   PermitSemanticHasher semanticHasher,
                                   AtfmScheduleGateway atfmScheduleGateway,
                                   AtfmDatabaseProperties properties,
                                   StringRedisTemplate redisTemplate) {
        this.permitImportRepository = permitImportRepository;
        this.syncJobRepository = syncJobRepository;
        this.semanticHasher = semanticHasher;
        this.atfmScheduleGateway = atfmScheduleGateway;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    public PermitImportOutcome importPermit(ProcessingContext context) {
        SchedulePermit permit = context.getSchedulePermit();
        Long syncJobId = context.getEvent().getSyncJobId();
        SyncJob job = syncJobRepository.findById(syncJobId)
                .orElseThrow(() -> new IllegalStateException("Sync job not found: " + syncJobId));
        String semanticHash = semanticHasher.hash(permit);
        PermitImport attempt = permitImportRepository.findBySyncJobId(syncJobId)
                .orElseGet(() -> reserve(job, permit, semanticHash));

        if (attempt.getStatus() == PermitImportStatus.SAVED
                || attempt.getStatus() == PermitImportStatus.DUPLICATE) {
            return outcome(attempt);
        }

        String lockKey = LOCK_PREFIX + permit.normalizedPermitId();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey, Long.toString(syncJobId), Duration.ofSeconds(properties.getPermitLockSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("Another worker is processing permit " + permit.normalizedPermitId());
        }

        try {
            Optional<PermitImport> savedImport = permitImportRepository
                    .findFirstByNormalizedPermitIdAndStatusInOrderByCreatedAtAsc(
                            permit.normalizedPermitId(),
                            List.of(PermitImportStatus.SAVED, PermitImportStatus.DUPLICATE));
            if (savedImport.isPresent()) {
                PermitImport original = savedImport.get();
                if (original.getSemanticHash().equals(semanticHash)) {
                    markDuplicate(attempt, original.getTargetMasterId(), original.getTargetPermId(), permit.flights().size());
                    return outcome(attempt);
                }
                markRevision(attempt, "Permit already exists with different schedule data");
                throw revisionException(permit);
            }

            if (!properties.isWriteEnabled()) {
                attempt.setStatus(PermitImportStatus.DRY_RUN);
                attempt.setDetailCount(permit.flights().size());
                attempt.setErrorMessage("ATFM writes are disabled");
                permitImportRepository.save(attempt);
                throw new BusinessRuleException(
                        "ATFM-WRITE-DISABLED",
                        "Permit parsed and validated, but ATFM writes are disabled");
            }

            Optional<AtfmPermitSnapshot> target = atfmScheduleGateway.findExisting(permit);
            if (target.isPresent()) {
                AtfmPermitSnapshot snapshot = target.get();
                if (snapshot.matchesExpectedPermit()) {
                    markDuplicate(attempt, snapshot.masterId(), snapshot.permId(), permit.flights().size());
                    return outcome(attempt);
                }
                markRevision(attempt, "Target ATFM permit exists with different schedule data");
                throw revisionException(permit);
            }

            AtfmWriteResult result = atfmScheduleGateway.insert(permit);
            attempt.setStatus(PermitImportStatus.SAVED);
            attempt.setTargetMasterId(result.masterId());
            attempt.setTargetPermId(result.permId());
            attempt.setDetailCount(result.detailCount());
            attempt.setErrorMessage(null);
            permitImportRepository.save(attempt);
            return outcome(attempt);
        } catch (BusinessRuleException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            attempt.setStatus(PermitImportStatus.FAILED);
            attempt.setErrorMessage(truncate(exception.getMessage()));
            permitImportRepository.save(attempt);
            throw exception;
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private PermitImport reserve(SyncJob job, SchedulePermit permit, String semanticHash) {
        PermitImport attempt = new PermitImport();
        attempt.setSyncJob(job);
        attempt.setNormalizedPermitId(permit.normalizedPermitId());
        attempt.setSemanticHash(semanticHash);
        attempt.setSourceFileHash(job.getFileHash());
        attempt.setStatus(PermitImportStatus.RESERVED);
        attempt.setDetailCount(permit.flights().size());
        return permitImportRepository.save(attempt);
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
        attempt.setErrorMessage(message);
        permitImportRepository.save(attempt);
    }

    private BusinessRuleException revisionException(SchedulePermit permit) {
        return new BusinessRuleException(
                "PERMIT-REVISION-REVIEW",
                "Permit %s was previously imported with different schedule data"
                        .formatted(permit.normalizedPermitId()));
    }

    private PermitImportOutcome outcome(PermitImport attempt) {
        return new PermitImportOutcome(
                attempt.getStatus(),
                attempt.getDetailCount() == null ? 0 : attempt.getDetailCount(),
                attempt.getTargetMasterId(),
                attempt.getTargetPermId());
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
    }
}
