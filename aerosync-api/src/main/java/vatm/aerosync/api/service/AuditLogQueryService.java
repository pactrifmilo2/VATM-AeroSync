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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<AuditLog> logs = stream
                .sorted(Comparator.comparing(AuditLog::getTimestamp).reversed())
                .limit(500)
                .toList();
        Map<Long, FileSourceType> sourceTypes = resolveSourceTypes(logs);
        return logs.stream()
                .map(log -> toResponse(log, sourceTypes))
                .filter(response -> source == null || response.sourceType() == source)
                .toList();
    }

    private Stream<AuditLog> loadLogs(LocalDateTime from, LocalDateTime to) {
        if (from == null && to == null) {
            // The dashboard refreshes frequently. Loading the complete audit
            // table here eventually exhausts the API process as data grows.
            return auditLogRepository.findTop500ByOrderByTimestampDesc().stream();
        }
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
        return Stream.empty();
    }

    private AuditLogResponse toResponse(AuditLog log, Map<Long, FileSourceType> sourceTypes) {
        Long syncJobId = log.getSyncJob() != null ? log.getSyncJob().getId() : null;
        boolean incomingNotice = log.getAction() != null && log.getAction().startsWith("INCOMING_");
        FileSourceType sourceType = incomingNotice ? FileSourceType.FILESYSTEM : sourceTypes.get(syncJobId);
        return new AuditLogResponse(
                log.getId(),
                syncJobId,
                log.getAction(),
                log.getResultStatus(),
                log.getTimestamp(),
                log.getDurationMs(),
                sourceType,
                incomingNotice ? log.getOutputSummary() : null);
    }

    private Map<Long, FileSourceType> resolveSourceTypes(List<AuditLog> logs) {
        List<Long> syncJobIds = logs.stream()
                .filter(log -> log.getSyncJob() != null)
                .map(log -> log.getSyncJob().getId())
                .distinct()
                .toList();
        if (syncJobIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FileSourceType> sourceTypes = new HashMap<>();
        for (FileRecord record : fileRecordRepository.findBySyncJobIdIn(syncJobIds)) {
            sourceTypes.putIfAbsent(record.getSyncJob().getId(), record.getSourceType());
        }
        return sourceTypes;
    }
}
