package vatm.aerosync.ingest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class EmailFailureTracker {

    private static final Logger log = LoggerFactory.getLogger(EmailFailureTracker.class);
    static final String REDIS_FAILURE_COUNT_KEY = "aerosync:email:consecutive-failures";
    static final int ALERT_THRESHOLD = 3;

    private final StringRedisTemplate redisTemplate;

    public EmailFailureTracker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void recordSuccess() {
        redisTemplate.delete(REDIS_FAILURE_COUNT_KEY);
    }

    public void recordFailure() {
        Long count = redisTemplate.opsForValue().increment(REDIS_FAILURE_COUNT_KEY);
        if (count != null && count >= ALERT_THRESHOLD) {
            log.error("ALT-05: Email server unavailable for {} consecutive ingest cycles", count);
        }
    }

    public int getConsecutiveFailures() {
        String value = redisTemplate.opsForValue().get(REDIS_FAILURE_COUNT_KEY);
        return value == null ? 0 : Integer.parseInt(value);
    }
}
