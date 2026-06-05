package vatm.aerosync.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.AuditLogResponse;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.FileRecordRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class AuditLogQueryService {

    private final AuditLogRepository auditLogRepository;
    private final FileRecordRepository fileRecordRepository;

    public AuditLogQueryService(AuditLogRepository auditLogRepository,
                                FileRecordRepository fileRecordRepository) {
        this.auditLogRepository = auditLogRepository;
        this.fileRecordRepository = fileRecordRepository;
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> search(LocalDateTime from,
                                         LocalDateTime to,
                                         SyncStatus status,
                                         FileSourceType source) {
        Stream<AuditLog> stream = loadLogs(from, to);
        if (status != null) {
            stream = stream.filter(log -> log.getResultStatus() == status);
        }
        return stream
                .sorted(Comparator.comparing(AuditLog::getTimestamp).reversed())
                .map(log -> toResponse(log, source))
                .filter(response -> source == null || response.sourceType() == source)
                .toList();
    }

    private Stream<AuditLog> loadLogs(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null) {
            return auditLogRepository.findByTimestampBetween(from, to).stream();
        }
        if (from != null) {
            LocalDateTime upper = to != null ? to : LocalDateTime.now().plusYears(10);
            return auditLogRepository.findByTimestampBetween(from, upper).stream();
        }
        if (to != null) {
            return auditLogRepository.findByTimestampBetween(LocalDateTime.MIN, to).stream();
        }
        return auditLogRepository.findAll().stream();
    }

    private AuditLogResponse toResponse(AuditLog log, FileSourceType sourceFilter) {
        Long syncJobId = log.getSyncJob() != null ? log.getSyncJob().getId() : null;
        FileSourceType sourceType = resolveSourceType(syncJobId);
        return new AuditLogResponse(
                log.getId(),
                syncJobId,
                log.getAction(),
                log.getResultStatus(),
                log.getTimestamp(),
                log.getDurationMs(),
                sourceType);
    }

    private FileSourceType resolveSourceType(Long syncJobId) {
        if (syncJobId == null) {
            return null;
        }
        return fileRecordRepository.findBySyncJobId(syncJobId).stream()
                .map(FileRecord::getSourceType)
                .findFirst()
                .orElse(null);
    }
}
