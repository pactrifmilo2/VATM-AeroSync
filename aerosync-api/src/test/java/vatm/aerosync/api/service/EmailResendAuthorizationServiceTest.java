package vatm.aerosync.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailResendAuthorizationServiceTest {

    @Test
    void authorize_registersTerminalJobHashWithoutDeletingHistory() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        SyncJob job = mock(SyncJob.class);
        when(job.getId()).thenReturn(41L);
        when(job.getFileHash()).thenReturn("sha-41");
        when(job.getStatus()).thenReturn(SyncStatus.SUCCESS);

        var hashes = new EmailResendAuthorizationService(redis).authorize(List.of(job));

        assertThat(hashes).containsExactly("sha-41");
        verify(values).set(eq("aerosync:resend:sha-41"), eq("41"), isA(Duration.class));
    }

    @Test
    void authorize_refusesActiveJob() {
        SyncJob job = mock(SyncJob.class);
        when(job.getId()).thenReturn(42L);
        when(job.getFileHash()).thenReturn("sha-42");
        when(job.getStatus()).thenReturn(SyncStatus.IN_PROGRESS);

        assertThatThrownBy(() -> new EmailResendAuthorizationService(mock(StringRedisTemplate.class))
                .authorize(List.of(job)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still active");
    }

    @Test
    void authorize_allowsPendingJobWhenLatestFileIsQuarantined() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        FileRecordRepository records = mock(FileRecordRepository.class);
        SyncJob job = mock(SyncJob.class);
        when(job.getId()).thenReturn(43L);
        when(job.getFileHash()).thenReturn("sha-43");
        when(job.getStatus()).thenReturn(SyncStatus.PENDING);
        FileRecord record = mock(FileRecord.class);
        when(record.getProcessingStatus()).thenReturn(FileProcessingStatus.QUARANTINED);
        when(records.findBySyncJobId(43L)).thenReturn(List.of(record));

        var hashes = new EmailResendAuthorizationService(redis, records).authorize(List.of(job));

        assertThat(hashes).containsExactly("sha-43");
        verify(values).set(eq("aerosync:resend:sha-43"), eq("43"), isA(Duration.class));
    }
}
