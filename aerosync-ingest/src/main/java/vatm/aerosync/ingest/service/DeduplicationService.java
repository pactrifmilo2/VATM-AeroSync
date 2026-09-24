package vatm.aerosync.ingest.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.PermitImportStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.PermitImportRepository;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.util.Optional;

@Service
public class DeduplicationService {

    static final String REDIS_KEY_PREFIX = "aerosync:dedup:";
    static final String RESEND_KEY_PREFIX = "aerosync:resend:";

    private final StringRedisTemplate redisTemplate;
    private final SyncJobRepository syncJobRepository;
    private final PermitImportRepository permitImportRepository;

    @Autowired
    public DeduplicationService(StringRedisTemplate redisTemplate,
                                SyncJobRepository syncJobRepository,
                                PermitImportRepository permitImportRepository) {
        this.redisTemplate = redisTemplate;
        this.syncJobRepository = syncJobRepository;
        this.permitImportRepository = permitImportRepository;
    }

    /** Compatibility constructor for focused tests. */
    public DeduplicationService(StringRedisTemplate redisTemplate, SyncJobRepository syncJobRepository) {
        this(redisTemplate, syncJobRepository, null);
    }

    public boolean isDuplicate(String fileHash) {
        Optional<SyncJob> existing = syncJobRepository.findByFileHash(fileHash);
        if (existing.isPresent()) {
            return true;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_KEY_PREFIX + fileHash));
    }

    public void registerHash(String fileHash) {
        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + fileHash, "1");
    }

    public JobCreationResult createPendingJob(String fileHash) {
        SyncJob job = new SyncJob();
        job.setFileHash(fileHash);
        job.setStatus(SyncStatus.PENDING);
        try {
            return new JobCreationResult(syncJobRepository.saveAndFlush(job), true);
        } catch (DataIntegrityViolationException exception) {
            SyncJob existing = syncJobRepository.findByFileHash(fileHash).orElseThrow(() -> exception);
            return new JobCreationResult(existing, false);
        }
    }

    public SyncJob createSkippedDuplicateJob(String fileHash) {
        return syncJobRepository.findByFileHash(fileHash)
                .orElseGet(() -> {
                    SyncJob job = new SyncJob();
                    job.setFileHash(fileHash);
                    job.setStatus(SyncStatus.SKIPPED);
                    try {
                        return syncJobRepository.saveAndFlush(job);
                    } catch (DataIntegrityViolationException exception) {
                        return syncJobRepository.findByFileHash(fileHash).orElseThrow(() -> exception);
                    }
                });
    }

    @Transactional
    public Optional<SyncJob> claimAuthorizedResend(String fileHash) {
        String key = RESEND_KEY_PREFIX + fileHash;
        String authorizedJobId = redisTemplate.opsForValue().get(key);
        if (authorizedJobId == null || authorizedJobId.isBlank()) {
            return Optional.empty();
        }
        SyncJob job;
        try {
            job = syncJobRepository.findById(Long.valueOf(authorizedJobId)).orElse(null);
        } catch (NumberFormatException exception) {
            redisTemplate.delete(key);
            return Optional.empty();
        }
        if (job == null || !fileHash.equalsIgnoreCase(job.getFileHash())
                || job.getStatus() == SyncStatus.PENDING || job.getStatus() == SyncStatus.IN_PROGRESS) {
            return Optional.empty();
        }
        job.setStatus(SyncStatus.PENDING);
        syncJobRepository.saveAndFlush(job);
        if (permitImportRepository != null) {
            PermitImport attempt = permitImportRepository.findBySyncJobId(job.getId()).orElse(null);
            if (attempt != null) {
                attempt.setStatus(PermitImportStatus.RESERVED);
                attempt.setErrorMessage(null);
                permitImportRepository.save(attempt);
            }
        }
        redisTemplate.delete(key);
        return Optional.of(job);
    }

    public record JobCreationResult(SyncJob job, boolean created) {
    }
}
