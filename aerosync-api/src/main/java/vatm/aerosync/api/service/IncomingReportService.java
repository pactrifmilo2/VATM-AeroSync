package vatm.aerosync.api.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.EmailReportRowResponse;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Provides Incoming-folder events in the row shape already used by EmailReports.aspx. */
@Service
public class IncomingReportService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String INCOMING_ACTION_PREFIX = "INCOMING_";
    private static final String DUPLICATE_ACTION = "INCOMING_DUPLICATE_SKIPPED";

    private final AuditLogRepository auditLogRepository;
    private final FileRecordRepository fileRecordRepository;
    private final PermitImportRepository permitImportRepository;
    private final VietnameseErrorMessageTranslator errorMessageTranslator;

    public IncomingReportService(AuditLogRepository auditLogRepository,
                                 FileRecordRepository fileRecordRepository,
                                 PermitImportRepository permitImportRepository,
                                 VietnameseErrorMessageTranslator errorMessageTranslator) {
        this.auditLogRepository = auditLogRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.permitImportRepository = permitImportRepository;
        this.errorMessageTranslator = errorMessageTranslator;
    }

    @Transactional(readOnly = true)
    public PagedResponse<EmailReportRowResponse> search(int page, int size) {
        validate(page, size);
        PageRequest request = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id")));
        Page<AuditLog> eventPage = auditLogRepository
                .findByActionStartingWith(INCOMING_ACTION_PREFIX, request);
        List<Long> syncJobIds = eventPage.getContent().stream()
                .filter(log -> log.getSyncJob() != null)
                .map(log -> log.getSyncJob().getId())
                .distinct()
                .toList();
        Map<Long, FileRecord> latestRecords = latestRecords(syncJobIds);
        Map<Long, String> permitNumbers = permitNumbers(syncJobIds);
        Page<EmailReportRowResponse> rows = eventPage.map(log ->
                toResponse(log, latestRecords, permitNumbers));
        return PagedResponse.from(rows);
    }

    private EmailReportRowResponse toResponse(AuditLog event,
                                              Map<Long, FileRecord> latestRecords,
                                              Map<Long, String> permitNumbers) {
        boolean duplicate = DUPLICATE_ACTION.equals(event.getAction());
        Long syncJobId = event.getSyncJob() != null ? event.getSyncJob().getId() : null;
        FileRecord record = syncJobId != null ? latestRecords.get(syncJobId) : null;
        String fileName = fileName(event.getInputSummary(), record);
        FileProcessingStatus fileStatus = duplicate
                ? FileProcessingStatus.SKIPPED
                : record != null ? record.getProcessingStatus() : FileProcessingStatus.DOWNLOADED;
        String errorMessage = duplicate
                ? event.getOutputSummary()
                : record != null ? errorMessageTranslator.translate(record.getErrorMessage()) : null;
        SyncStatus jobStatus = duplicate
                ? SyncStatus.SKIPPED
                : event.getSyncJob() != null ? event.getSyncJob().getStatus() : event.getResultStatus();

        return new EmailReportRowResponse(
                event.getId(),
                syncJobId,
                duplicate || syncJobId == null ? null : permitNumbers.get(syncJobId),
                "FILESYSTEM-" + event.getId(),
                "Thư mục Incoming",
                fileName,
                event.getTimestamp(),
                1,
                0,
                fileName,
                record != null ? StoredFileName.from(record) : fileName,
                errorMessage,
                toEmailStatus(fileStatus),
                null,
                isComplete(fileStatus),
                null,
                jobStatus);
    }

    private Map<Long, FileRecord> latestRecords(List<Long> syncJobIds) {
        if (syncJobIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, FileRecord> records = new HashMap<>();
        for (FileRecord candidate : fileRecordRepository.findBySyncJobIdIn(syncJobIds)) {
            records.merge(candidate.getSyncJob().getId(), candidate, this::newerRecord);
        }
        return records;
    }

    private Map<Long, String> permitNumbers(List<Long> syncJobIds) {
        if (syncJobIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> numbers = new HashMap<>();
        permitImportRepository.findBySyncJobIdIn(syncJobIds).forEach(permitImport ->
                numbers.put(permitImport.getSyncJob().getId(), permitImport.getNormalizedPermitId()));
        return numbers;
    }

    private FileRecord newerRecord(FileRecord first, FileRecord second) {
        Comparator<LocalDateTime> timestamps = Comparator.nullsFirst(Comparator.naturalOrder());
        int result = timestamps.compare(first.getCreatedAt(), second.getCreatedAt());
        if (result != 0) {
            return result >= 0 ? first : second;
        }
        return first.getId() != null && second.getId() != null && first.getId() >= second.getId()
                ? first : second;
    }

    private String fileName(String storedPath, FileRecord record) {
        if (storedPath != null && !storedPath.isBlank()) {
            try {
                return Path.of(storedPath).getFileName().toString();
            } catch (RuntimeException ignored) {
                // Fall through to the persisted file record.
            }
        }
        return record != null ? record.getOriginalFileName() : "(không xác định)";
    }

    private EmailProcessingStatus toEmailStatus(FileProcessingStatus status) {
        return switch (status) {
            case DISCOVERED, DOWNLOADED -> EmailProcessingStatus.DOWNLOADED;
            case PROCESSING -> EmailProcessingStatus.PROCESSING;
            case SAVED -> EmailProcessingStatus.SAVED;
            case FAILED -> EmailProcessingStatus.FAILED;
            case QUARANTINED -> EmailProcessingStatus.QUARANTINED;
            case SKIPPED -> EmailProcessingStatus.SKIPPED;
        };
    }

    private boolean isComplete(FileProcessingStatus status) {
        return status == FileProcessingStatus.SAVED
                || status == FileProcessingStatus.FAILED
                || status == FileProcessingStatus.QUARANTINED
                || status == FileProcessingStatus.SKIPPED;
    }

    private void validate(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("'page' must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("'size' must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
