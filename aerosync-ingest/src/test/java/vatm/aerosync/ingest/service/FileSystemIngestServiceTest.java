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
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.AuditLogRepository;
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
    private FileRecordRepository fileRecordRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AuditLogRepository auditLogRepository;

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
                fileRecordRepository,
                redisTemplate,
                auditLogRepository);
    }

    @Test
    void ingestUpTo_usesIncomingPathFromFilePathProperties() throws Exception {
        Files.writeString(tempDir.resolve("flight.csv"), "callsign,from,to\nVN123,HAN,SGN");
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        stubNewPendingJob();

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(1);
        verify(ingestPublisher).publish(any(FileIngestedEvent.class));
    }

    @Test
    void ingestUpTo_skipsDuplicateHash() throws Exception {
        Files.writeString(tempDir.resolve("dup.csv"), "duplicate-content");
        String hash = Hashing.sha256Hex(tempDir.resolve("dup.csv"));
        when(deduplicationService.isDuplicate(hash)).thenReturn(true);

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(0);
        verify(deduplicationService).createSkippedDuplicateJob(hash);
        verify(auditLogRepository).save(org.mockito.ArgumentMatchers.argThat(log ->
                "INCOMING_DUPLICATE_SKIPPED".equals(log.getAction())
                        && log.getOutputSummary().contains("dup.csv")));
        verify(ingestPublisher, never()).publish(any());
    }

    @Test
    void ingestUpTo_doesNotRepublishPendingJob() throws Exception {
        Path file = tempDir.resolve("retry.csv");
        Files.writeString(file, "callsign,from,to,dateflight\nVN123,HAN,SGN,2026-01-01");
        String hash = Hashing.sha256Hex(file);
        SyncJob pending = new SyncJob();
        pending.setFileHash(hash);
        pending.setStatus(vatm.aerosync.common.enums.SyncStatus.PENDING);
        ReflectionTestUtils.setField(pending, "id", 9L);
        when(deduplicationService.isDuplicate(hash)).thenReturn(true);
        when(deduplicationService.createSkippedDuplicateJob(hash)).thenReturn(pending);

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isZero();
        verify(ingestPublisher, never()).publish(any());
        verify(deduplicationService).createSkippedDuplicateJob(hash);
    }

    @Test
    void ingestUpTo_skipsAlreadySeenPath() throws Exception {
        Path file = tempDir.resolve("seen.csv");
        Files.writeString(file, "already-processed");
        String pathKey = "aerosync:ingest:fs-path:" + file.toAbsolutePath().normalize();
        when(valueOperations.get(pathKey)).thenReturn("1");

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isEqualTo(0);
        verify(ingestPublisher, never()).publish(any());
    }

    @Test
    void ingestUpTo_doesNotRepublishSeenPath() throws Exception {
        Path file = tempDir.resolve("seen-retry.csv");
        Files.writeString(file, "callsign,from,to,dateflight\nVN123,HAN,SGN,2026-01-01");
        String pathKey = "aerosync:ingest:fs-path:" + file.toAbsolutePath().normalize();
        when(valueOperations.get(pathKey)).thenReturn("1");

        int ingested = fileSystemIngestService.ingestUpTo(10);

        assertThat(ingested).isZero();
        verify(ingestPublisher, never()).publish(any());
    }

    @Test
    void ingestUpTo_respectsLimit() throws Exception {
        Files.writeString(tempDir.resolve("a.csv"), "a");
        Files.writeString(tempDir.resolve("b.csv"), "b");
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        stubNewPendingJob();

        int ingested = fileSystemIngestService.ingestUpTo(1);

        assertThat(ingested).isEqualTo(1);
        verify(ingestPublisher).publish(any(FileIngestedEvent.class));
    }

    @Test
    void ingestUpTo_publishesFilesystemSourceType() throws Exception {
        Files.writeString(tempDir.resolve("data.json"), "{}");
        when(deduplicationService.isDuplicate(anyString())).thenReturn(false);
        stubNewPendingJob();

        fileSystemIngestService.ingestUpTo(5);

        verify(ingestPublisher).publish(org.mockito.ArgumentMatchers.argThat(event ->
                event.getSourceType() == FileSourceType.FILESYSTEM));
        verify(fileRecordRepository).save(org.mockito.ArgumentMatchers.argThat(record ->
                record.getSourceType() == FileSourceType.FILESYSTEM
                        && record.getProcessingStatus() == FileProcessingStatus.DOWNLOADED
                        && record.getArchiveStatus() == FileArchiveStatus.PENDING
                        && record.getDownloadedAt() != null
                        && record.getFileSize() == 2L
                        && record.getChecksum().length() == 64));
        verify(auditLogRepository).save(org.mockito.ArgumentMatchers.argThat(log ->
                "INCOMING_FILE_ACCEPTED".equals(log.getAction())
                        && log.getOutputSummary().contains("data.json")));
    }

    private void stubNewPendingJob() {
        when(deduplicationService.createPendingJob(anyString())).thenAnswer(invocation -> {
            SyncJob job = new SyncJob();
            job.setFileHash(invocation.getArgument(0));
            ReflectionTestUtils.setField(job, "id", 1L);
            return new DeduplicationService.JobCreationResult(job, true);
        });
    }
}
