package vatm.aerosync.ingest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import org.springframework.test.util.ReflectionTestUtils;
import vatm.aerosync.ingest.support.Hashing;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileSystemIngestServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private DeduplicationService deduplicationService;

    @Mock
    private IngestPublisher ingestPublisher;

    @Mock
    private SyncJobRepository syncJobRepository;

    @Mock
    private FileRecordRepository fileRecordRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private FilePathProperties filePathProperties;
    private FileSystemIngestService fileSystemIngestService;

    @BeforeEach
    void setUp() {
        filePathProperties = new FilePathProperties();
        filePathProperties.setIncoming(tempDir.toString());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        fileSystemIngestService = new FileSystemIngestService(
                filePathProperties,
                deduplicationService,
                ingestPublisher,
                syncJobRepository,
                fileRecordRepository,
                redisTemplate);
    }

    @Test
    void ingestUpTo_usesIncomingPathFromFilePathProperties() throws Exception {
        Files.writeString(tempDir.resolve("flight.csv"), "callsign,from,to\nVN123,HAN,SGN");
        when(deduplicationService.findRetryableJob(anyString())).thenReturn(java.util.Optional.empty());
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        when(syncJobRepository.save(any())).thenAnswer(inv -> {
            SyncJob job = inv.getArgument(0);
            ReflectionTestUtils.setField(job, "id", 1L);
            return job;
        });

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(1);
        verify(ingestPublisher).publish(any(FileIngestedEvent.class));
    }

    @Test
    void ingestUpTo_skipsDuplicateHash() throws Exception {
        Files.writeString(tempDir.resolve("dup.csv"), "duplicate-content");
        String hash = Hashing.sha256Hex(tempDir.resolve("dup.csv"));
        when(deduplicationService.findRetryableJob(hash)).thenReturn(java.util.Optional.empty());
        when(deduplicationService.isDuplicate(hash)).thenReturn(true);

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(0);
        verify(deduplicationService).createSkippedDuplicateJob(hash);
        verify(ingestPublisher, never()).publish(any());
    }

    @Test
    void ingestUpTo_republishesPendingJob() throws Exception {
        Path file = tempDir.resolve("retry.csv");
        Files.writeString(file, "callsign,from,to,dateflight\nVN123,HAN,SGN,2026-01-01");
        String hash = Hashing.sha256Hex(file);
        SyncJob pending = new SyncJob();
        pending.setFileHash(hash);
        pending.setStatus(vatm.aerosync.common.enums.SyncStatus.PENDING);
        ReflectionTestUtils.setField(pending, "id", 9L);
        FileRecord record = new FileRecord();
        record.setSyncJob(pending);
        record.setStoredPath(file.toString());
        record.setOriginalFileName("retry.csv");
        record.setSourceType(FileSourceType.FILESYSTEM);
        when(deduplicationService.findRetryableJob(hash)).thenReturn(java.util.Optional.of(pending));
        when(fileRecordRepository.findBySyncJobId(9L)).thenReturn(java.util.List.of(record));

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(1);
        verify(ingestPublisher).publish(org.mockito.ArgumentMatchers.argThat(event ->
                event.getSyncJobId().equals(9L) && event.getTempFilePath().equals(file.toString())));
        verify(deduplicationService, never()).createSkippedDuplicateJob(anyString());
    }

    @Test
    void ingestUpTo_skipsAlreadySeenPath() throws Exception {
        Path file = tempDir.resolve("seen.csv");
        Files.writeString(file, "already-processed");
        String pathKey = "aerosync:ingest:fs-path:" + file.toAbsolutePath().normalize();
        String hash = Hashing.sha256Hex(file);
        when(valueOperations.get(pathKey)).thenReturn("1");
        when(deduplicationService.findRetryableJob(hash)).thenReturn(java.util.Optional.empty());

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(0);
        verify(ingestPublisher, never()).publish(any());
    }

    @Test
    void ingestUpTo_republishesSeenPathWhenJobIsRetryable() throws Exception {
        Path file = tempDir.resolve("seen-retry.csv");
        Files.writeString(file, "callsign,from,to,dateflight\nVN123,HAN,SGN,2026-01-01");
        String pathKey = "aerosync:ingest:fs-path:" + file.toAbsolutePath().normalize();
        String hash = Hashing.sha256Hex(file);
        SyncJob pending = new SyncJob();
        pending.setFileHash(hash);
        pending.setStatus(vatm.aerosync.common.enums.SyncStatus.PENDING);
        ReflectionTestUtils.setField(pending, "id", 11L);
        FileRecord record = new FileRecord();
        record.setSyncJob(pending);
        record.setStoredPath(file.toString());
        record.setOriginalFileName("seen-retry.csv");
        record.setSourceType(FileSourceType.FILESYSTEM);
        when(valueOperations.get(pathKey)).thenReturn("1");
        when(deduplicationService.findRetryableJob(hash)).thenReturn(java.util.Optional.of(pending));
        when(fileRecordRepository.findBySyncJobId(11L)).thenReturn(java.util.List.of(record));

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(1);
        verify(ingestPublisher).publish(any(FileIngestedEvent.class));
    }

    @Test
    void ingestUpTo_respectsLimit() throws Exception {
        Files.writeString(tempDir.resolve("a.csv"), "a");
        Files.writeString(tempDir.resolve("b.csv"), "b");
        when(deduplicationService.findRetryableJob(anyString())).thenReturn(java.util.Optional.empty());
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        when(syncJobRepository.save(any())).thenAnswer(inv -> {
            SyncJob job = inv.getArgument(0);
            ReflectionTestUtils.setField(job, "id", 1L);
            return job;
        });

        int ingested = fileSystemIngestService.ingestUpTo(1);

        assertThat(ingested).isEqualTo(1);
        verify(ingestPublisher).publish(any(FileIngestedEvent.class));
    }

    @Test
    void ingestUpTo_publishesFilesystemSourceType() throws Exception {
        Files.writeString(tempDir.resolve("data.json"), "{}");
        when(deduplicationService.findRetryableJob(anyString())).thenReturn(java.util.Optional.empty());
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        when(syncJobRepository.save(any())).thenAnswer(inv -> {
            SyncJob job = inv.getArgument(0);
            ReflectionTestUtils.setField(job, "id", 1L);
            return job;
        });

        fileSystemIngestService.ingestUpTo(5);

        verify(ingestPublisher).publish(org.mockito.ArgumentMatchers.argThat(event ->
                event.getSourceType() == FileSourceType.FILESYSTEM));
        verify(fileRecordRepository).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getSourceType() == FileSourceType.FILESYSTEM));
    }
}
