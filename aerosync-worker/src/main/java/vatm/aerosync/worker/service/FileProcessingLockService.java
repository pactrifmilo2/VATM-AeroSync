package vatm.aerosync.worker.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.worker.config.WorkerProperties;

import java.time.Duration;

@Service
public class FileProcessingLockService {

    static final String LOCK_PREFIX = "aerosync:worker:lock:";

    private final StringRedisTemplate redisTemplate;
    private final WorkerProperties workerProperties;

    public FileProcessingLockService(StringRedisTemplate redisTemplate, WorkerProperties workerProperties) {
        this.redisTemplate = redisTemplate;
        this.workerProperties = workerProperties;
    }

    public boolean tryAcquire(Long syncJobId) {
        String key = LOCK_PREFIX + syncJobId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(workerProperties.getLockTtlSeconds()));
        return Boolean.TRUE.equals(acquired);
    }

    public void release(Long syncJobId) {
        redisTemplate.delete(LOCK_PREFIX + syncJobId);
    }
}
