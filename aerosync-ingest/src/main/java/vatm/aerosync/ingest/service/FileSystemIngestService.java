package vatm.aerosync.ingest.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.config.FilePathProperties;
import vatm.aerosync.common.debug.DebugSessionLog;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.FileArchiveStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.ingest.support.Hashing;
import vatm.aerosync.ingest.support.PriorityDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class FileSystemIngestService {

    static final String PATH_SEEN_PREFIX = "aerosync:ingest:fs-path:";

    private final FilePathProperties filePathProperties;
    private final DeduplicationService deduplicationService;
    private final IngestPublisher ingestPublisher;
    private final FileRecordRepository fileRecordRepository;
    private final StringRedisTemplate redisTemplate;
    private final AuditLogRepository auditLogRepository;

    public FileSystemIngestService(FilePathProperties filePathProperties,
                                 DeduplicationService deduplicationService,
                                 IngestPublisher ingestPublisher,
                                 FileRecordRepository fileRecordRepository,
                                 StringRedisTemplate redisTemplate,
                                 AuditLogRepository auditLogRepository) {
        this.filePathProperties = filePathProperties;
        this.deduplicationService = deduplicationService;
        this.ingestPublisher = ingestPublisher;
        this.fileRecordRepository = fileRecordRepository;
        this.redisTemplate = redisTemplate;
        this.auditLogRepository = auditLogRepository;
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
                String hash = Hashing.sha256Hex(file);
                if (shouldSkipSeenPath(file, hash)) {
                    continue;
                }
                if (ingestFile(file, hash)) {
                    ingested++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan incoming directory: " + incoming, e);
        }
        return ingested;
    }

    private boolean isPathAlreadySeen(Path file, String currentHash) {
        String key = PATH_SEEN_PREFIX + file.toAbsolutePath().normalize();
        return currentHash.equals(redisTemplate.opsForValue().get(key));
    }

    private boolean shouldSkipSeenPath(Path file, String currentHash) {
        return isPathAlreadySeen(file, currentHash);
    }

    private void markPathSeen(Path file, String fileHash) {
        redisTemplate.opsForValue().set(
                PATH_SEEN_PREFIX + file.toAbsolutePath().normalize(), fileHash);
    }

    private boolean ingestFile(Path file, String hash) {
        if (deduplicationService.isDuplicate(hash)) {
            DebugSessionLog.log("D", "FileSystemIngestService.java:ingestFile", "duplicate hash skipped",
                    DebugSessionLog.map("file", file.getFileName().toString(), "hash", hash));
            SyncJob duplicateJob = deduplicationService.createSkippedDuplicateJob(hash);
            saveIncomingNotice(duplicateJob, "INCOMING_DUPLICATE_SKIPPED", SyncStatus.SKIPPED,
                    file, "Đã đọc file từ Incoming nhưng bỏ qua vì nội dung trùng SHA-256: "
                            + file.getFileName());
            markPathSeen(file, hash);
            return false;
        }

        DeduplicationService.JobCreationResult creation = deduplicationService.createPendingJob(hash);
        SyncJob saved = creation.job();
        if (!creation.created()) {
            markPathSeen(file, hash);
            return false;
        }

        FileRecord record = new FileRecord();
        record.setSyncJob(saved);
        record.setSourceType(FileSourceType.FILESYSTEM);
        record.setOriginalFileName(file.getFileName().toString());
        record.setStoredPath(file.toAbsolutePath().normalize().toString());
        markDownloaded(record, file, hash);
        saved.addFileRecord(record);
        fileRecordRepository.save(record);

        deduplicationService.registerHash(hash);
        markPathSeen(file, hash);

        boolean priority = PriorityDetector.isPriority(file.getFileName().toString(), null);
        FileIngestedEvent event = new FileIngestedEvent(
                saved.getId(),
                record.getStoredPath(),
                hash,
                FileSourceType.FILESYSTEM,
                priority);
        ingestPublisher.publish(event);
        saveIncomingNotice(saved, "INCOMING_FILE_ACCEPTED", SyncStatus.PENDING,
                file, "Đã nhận file từ Incoming và đưa vào hàng đợi xử lý: " + file.getFileName());
        DebugSessionLog.log("B", "FileSystemIngestService.java:ingestFile", "published ingest event",
                DebugSessionLog.map("syncJobId", saved.getId(), "path", record.getStoredPath()));
        return true;
    }

    private void saveIncomingNotice(SyncJob job,
                                    String action,
                                    SyncStatus status,
                                    Path file,
                                    String message) {
        AuditLog auditLog = new AuditLog();
        auditLog.setSyncJob(job);
        auditLog.setAction(action);
        auditLog.setInputSummary(file.toAbsolutePath().normalize().toString());
        auditLog.setOutputSummary(message);
        auditLog.setResultStatus(status);
        auditLogRepository.save(auditLog);
    }

    private void markDownloaded(FileRecord record, Path file, String checksum) {
        record.setProcessingStatus(FileProcessingStatus.DOWNLOADED);
        record.setDownloadedAt(LocalDateTime.now());
        record.setRowsSaved(null);
        record.setDatabaseSavedAt(null);
        record.setArchiveStatus(FileArchiveStatus.PENDING);
        record.setArchivedAt(null);
        record.setErrorMessage(null);
        record.setFileSize(fileSize(file));
        record.setChecksum(checksum);
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file size: " + file, e);
        }
    }
}
