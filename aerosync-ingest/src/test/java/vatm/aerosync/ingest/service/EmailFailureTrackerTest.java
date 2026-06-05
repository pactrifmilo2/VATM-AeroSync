package vatm.aerosync.ingest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailFailureTrackerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private EmailFailureTracker emailFailureTracker;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        emailFailureTracker = new EmailFailureTracker(redisTemplate);
    }

    @Test
    void recordSuccess_clearsFailureCounter() {
        emailFailureTracker.recordSuccess();

        verify(redisTemplate).delete(EmailFailureTracker.REDIS_FAILURE_COUNT_KEY);
    }

    @Test
    void recordFailure_incrementsCounterInRedis() {
        when(valueOperations.increment(EmailFailureTracker.REDIS_FAILURE_COUNT_KEY)).thenReturn(2L);

        emailFailureTracker.recordFailure();

        verify(valueOperations).increment(EmailFailureTracker.REDIS_FAILURE_COUNT_KEY);
    }

    @Test
    void getConsecutiveFailures_returnsZeroWhenKeyMissing() {
        when(valueOperations.get(EmailFailureTracker.REDIS_FAILURE_COUNT_KEY)).thenReturn(null);

        assertThat(emailFailureTracker.getConsecutiveFailures()).isZero();
    }
}
