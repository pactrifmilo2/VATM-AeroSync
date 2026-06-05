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
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.SyncJobRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeduplicationServiceTest {

    private static final String HASH = "abc123sha256";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SyncJobRepository syncJobRepository;

    private DeduplicationService deduplicationService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        deduplicationService = new DeduplicationService(redisTemplate, syncJobRepository);
    }

    @Test
    void isDuplicate_returnsTrueWhenHashExistsInRedisWithoutDatabaseRecord() {
        when(syncJobRepository.findByFileHash(HASH)).thenReturn(Optional.empty());
        when(redisTemplate.hasKey("aerosync:dedup:" + HASH)).thenReturn(true);

        assertThat(deduplicationService.isDuplicate(HASH)).isTrue();
    }

    @Test
    void isDuplicate_returnsFalseWhenHashExistsInRedisButJobFailed() {
        SyncJob failed = new SyncJob();
        failed.setFileHash(HASH);
        failed.setStatus(SyncStatus.FAILED);
        when(syncJobRepository.findByFileHash(HASH)).thenReturn(Optional.of(failed));
        when(redisTemplate.hasKey("aerosync:dedup:" + HASH)).thenReturn(true);

        assertThat(deduplicationService.isDuplicate(HASH)).isFalse();
    }

    @Test
    void isDuplicate_returnsTrueWhenHashExistsInDatabaseWithCompletedStatus() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        SyncJob existing = new SyncJob();
        existing.setFileHash(HASH);
        existing.setStatus(SyncStatus.SUCCESS);
        when(syncJobRepository.findByFileHash(HASH)).thenReturn(Optional.of(existing));

        assertThat(deduplicationService.isDuplicate(HASH)).isTrue();
    }

    @Test
    void isDuplicate_returnsFalseWhenHashExistsWithPendingStatus() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        SyncJob existing = new SyncJob();
        existing.setFileHash(HASH);
        existing.setStatus(SyncStatus.PENDING);
        when(syncJobRepository.findByFileHash(HASH)).thenReturn(Optional.of(existing));

        assertThat(deduplicationService.isDuplicate(HASH)).isFalse();
    }

    @Test
    void findRetryableJob_returnsPendingJob() {
        SyncJob pending = new SyncJob();
        pending.setFileHash(HASH);
        pending.setStatus(SyncStatus.PENDING);
        when(syncJobRepository.findByFileHash(HASH)).thenReturn(Optional.of(pending));

        assertThat(deduplicationService.findRetryableJob(HASH)).contains(pending);
    }

    @Test
    void isDuplicate_returnsFalseForNewHash() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(syncJobRepository.findByFileHash(HASH)).thenReturn(Optional.empty());

        assertThat(deduplicationService.isDuplicate(HASH)).isFalse();
    }

    @Test
    void registerHash_storesKeyInRedis() {
        deduplicationService.registerHash(HASH);

        verify(valueOperations).set(eq("aerosync:dedup:" + HASH), eq("1"));
    }

    @Test
    void createSkippedDuplicateJob_persistsJobWithSkippedStatus() {
        when(syncJobRepository.findByFileHash(HASH)).thenReturn(Optional.empty());
        when(syncJobRepository.save(org.mockito.ArgumentMatchers.any(SyncJob.class)))
                .thenAnswer(invocation -> {
                    SyncJob job = invocation.getArgument(0);
                    return job;
                });

        SyncJob job = deduplicationService.createSkippedDuplicateJob(HASH);

        assertThat(job.getFileHash()).isEqualTo(HASH);
        assertThat(job.getStatus()).isEqualTo(SyncStatus.SKIPPED);
        verify(syncJobRepository).save(job);
    }

    @Test
    void createSkippedDuplicateJob_returnsExistingJobWithoutInsertingAgain() {
        SyncJob existing = new SyncJob();
        existing.setFileHash(HASH);
        existing.setStatus(SyncStatus.SUCCESS);
        when(syncJobRepository.findByFileHash(HASH)).thenReturn(Optional.of(existing));

        SyncJob job = deduplicationService.createSkippedDuplicateJob(HASH);

        assertThat(job).isSameAs(existing);
        verify(syncJobRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
