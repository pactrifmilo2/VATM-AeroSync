package vatm.aerosync.ingest.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.util.Optional;

@Service
public class DeduplicationService {

    static final String REDIS_KEY_PREFIX = "aerosync:dedup:";

    private final StringRedisTemplate redisTemplate;
    private final SyncJobRepository syncJobRepository;

    public DeduplicationService(StringRedisTemplate redisTemplate, SyncJobRepository syncJobRepository) {
        this.redisTemplate = redisTemplate;
        this.syncJobRepository = syncJobRepository;
    }

    public boolean isDuplicate(String fileHash) {
        Optional<SyncJob> existing = syncJobRepository.findByFileHash(fileHash);
        if (existing.isPresent()) {
            SyncStatus status = existing.get().getStatus();
            return status == SyncStatus.SUCCESS || status == SyncStatus.SKIPPED;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_KEY_PREFIX + fileHash));
    }

    public Optional<SyncJob> findRetryableJob(String fileHash) {
        return syncJobRepository.findByFileHash(fileHash)
                .filter(job -> job.getStatus() == SyncStatus.PENDING || job.getStatus() == SyncStatus.FAILED);
    }

    public void registerHash(String fileHash) {
        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + fileHash, "1");
    }

    public SyncJob createSkippedDuplicateJob(String fileHash) {
        return syncJobRepository.findByFileHash(fileHash)
                .orElseGet(() -> {
                    SyncJob job = new SyncJob();
                    job.setFileHash(fileHash);
                    job.setStatus(SyncStatus.SKIPPED);
                    return syncJobRepository.save(job);
                });
    }
}
