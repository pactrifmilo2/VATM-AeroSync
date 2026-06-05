package vatm.aerosync.ingest.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import java.util.Optional;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.SyncJobRepository;
import vatm.aerosync.ingest.support.Hashing;
import vatm.aerosync.ingest.support.PriorityDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class FileSystemIngestService {

    static final String PATH_SEEN_PREFIX = "aerosync:ingest:fs-path:";

    private final FilePathProperties filePathProperties;
    private final DeduplicationService deduplicationService;
    private final IngestPublisher ingestPublisher;
    private final SyncJobRepository syncJobRepository;
    private final FileRecordRepository fileRecordRepository;
    private final StringRedisTemplate redisTemplate;

    public FileSystemIngestService(FilePathProperties filePathProperties,
                                 DeduplicationService deduplicationService,
                                 IngestPublisher ingestPublisher,
                                 SyncJobRepository syncJobRepository,
                                 FileRecordRepository fileRecordRepository,
                                 StringRedisTemplate redisTemplate) {
        this.filePathProperties = filePathProperties;
        this.deduplicationService = deduplicationService;
        this.ingestPublisher = ingestPublisher;
        this.syncJobRepository = syncJobRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.redisTemplate = redisTemplate;
    }

    public int ingestUpTo(int limit) {
        Path incoming = Path.of(filePathProperties.getIncoming());
        if (!Files.isDirectory(incoming)) {
            return 0;
        }

        int ingested = 0;
        try (Stream<Path> paths = Files.list(incoming)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::getFileName)).toList()) {
                if (ingested >= limit) {
                    break;
                }
                if (shouldSkipSeenPath(file)) {
                    continue;
                }
                if (ingestFile(file)) {
                    ingested++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan incoming directory: " + incoming, e);
        }
        return ingested;
    }

    private boolean isPathAlreadySeen(Path file) {
        String key = PATH_SEEN_PREFIX + file.toAbsolutePath().normalize();
        return redisTemplate.opsForValue().get(key) != null;
    }

    private boolean shouldSkipSeenPath(Path file) {
        if (!isPathAlreadySeen(file)) {
            return false;
        }
        return deduplicationService.findRetryableJob(Hashing.sha256Hex(file)).isEmpty();
    }

    private void markPathSeen(Path file) {
        redisTemplate.opsForValue().set(PATH_SEEN_PREFIX + file.toAbsolutePath().normalize(), "1");
    }

    private boolean ingestFile(Path file) {
        String hash = Hashing.sha256Hex(file);
        Optional<SyncJob> retryableJob = deduplicationService.findRetryableJob(hash);
        if (retryableJob.isPresent()) {
            republishExistingJob(retryableJob.get(), hash, file);
            return true;
        }
        if (deduplicationService.isDuplicate(hash)) {
            DebugSessionLog.log("D", "FileSystemIngestService.java:ingestFile", "duplicate hash skipped",
                    DebugSessionLog.map("file", file.getFileName().toString(), "hash", hash));
            deduplicationService.createSkippedDuplicateJob(hash);
            markPathSeen(file);
            return false;
        }

        SyncJob job = new SyncJob();
        job.setFileHash(hash);
        job.setStatus(SyncStatus.PENDING);
        SyncJob saved = syncJobRepository.save(job);

        FileRecord record = new FileRecord();
        record.setSyncJob(saved);
        record.setSourceType(FileSourceType.FILESYSTEM);
        record.setOriginalFileName(file.getFileName().toString());
        record.setStoredPath(file.toAbsolutePath().normalize().toString());
        saved.addFileRecord(record);
        fileRecordRepository.save(record);

        deduplicationService.registerHash(hash);
        markPathSeen(file);

        boolean priority = PriorityDetector.isPriority(file.getFileName().toString(), null);
        FileIngestedEvent event = new FileIngestedEvent(
                saved.getId(),
                record.getStoredPath(),
                hash,
                FileSourceType.FILESYSTEM,
                priority);
        ingestPublisher.publish(event);
        DebugSessionLog.log("B", "FileSystemIngestService.java:ingestFile", "published ingest event",
                DebugSessionLog.map("syncJobId", saved.getId(), "path", record.getStoredPath()));
        return true;
    }

    private void republishExistingJob(SyncJob job, String hash, Path file) {
        FileRecord record = fileRecordRepository.findBySyncJobId(job.getId()).stream()
                .max(Comparator.comparing(FileRecord::getCreatedAt))
                .orElseThrow(() -> new IllegalStateException("No file records for job: " + job.getId()));
        boolean priority = PriorityDetector.isPriority(record.getOriginalFileName(), null);
        FileIngestedEvent event = new FileIngestedEvent(
                job.getId(),
                record.getStoredPath(),
                hash,
                FileSourceType.FILESYSTEM,
                priority);
        ingestPublisher.publish(event);
        markPathSeen(file);
        DebugSessionLog.log("D", "FileSystemIngestService.java:republishExistingJob", "republished stuck job",
                DebugSessionLog.map("syncJobId", job.getId(), "status", job.getStatus().name(),
                        "path", record.getStoredPath()));
    }
}
