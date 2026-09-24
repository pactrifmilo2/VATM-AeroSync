package vatm.aerosync.api.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Authorizes one deduplication bypass without deleting the original job or ATFM data. */
@Service
public class EmailResendAuthorizationService {

    public static final String REDIS_PREFIX = "aerosync:resend:";
    private static final Duration AUTHORIZATION_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final FileRecordRepository fileRecordRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public EmailResendAuthorizationService(StringRedisTemplate redisTemplate,
                                            FileRecordRepository fileRecordRepository) {
        this.redisTemplate = redisTemplate;
        this.fileRecordRepository = fileRecordRepository;
    }

    /** Compatibility constructor for focused unit tests. */
    public EmailResendAuthorizationService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, null);
    }

    public Set<String> authorize(Collection<SyncJob> jobs) {
        Set<String> hashes = new LinkedHashSet<>();
        for (SyncJob job : jobs) {
            validateTerminal(job);
            String hash = job.getFileHash();
            redisTemplate.opsForValue().set(
                    REDIS_PREFIX + hash,
                    Long.toString(job.getId()),
                    AUTHORIZATION_TTL);
            hashes.add(hash);
        }
        return hashes;
    }

    public void revoke(Collection<String> hashes) {
        hashes.forEach(hash -> redisTemplate.delete(REDIS_PREFIX + hash));
    }

    private void validateTerminal(SyncJob job) {
        if (job == null || job.getId() == null || job.getFileHash() == null || job.getFileHash().isBlank()) {
            throw new IllegalStateException("Cannot authorize resend for a synchronization job without an ID/hash");
        }
        if (job.getStatus() == SyncStatus.IN_PROGRESS
                || (job.getStatus() == SyncStatus.PENDING && !hasTerminalFile(job.getId()))) {
            throw new IllegalStateException("Sync job " + job.getId() + " is still active and cannot be resent");
        }
    }

    private boolean hasTerminalFile(Long syncJobId) {
        if (fileRecordRepository == null || syncJobId == null) {
            return false;
        }
        return fileRecordRepository.findBySyncJobId(syncJobId).stream()
                .max(Comparator.comparing(FileRecord::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(FileRecord::getProcessingStatus)
                .map(status -> status == FileProcessingStatus.FAILED
                        || status == FileProcessingStatus.QUARANTINED
                        || status == FileProcessingStatus.SKIPPED)
                .orElse(false);
    }
}
