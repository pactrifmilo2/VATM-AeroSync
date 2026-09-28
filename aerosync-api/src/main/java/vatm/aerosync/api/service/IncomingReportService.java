package vatm.aerosync.api.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.EmailReportRowResponse;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.EmailReportSummaryResponse;
import vatm.aerosync.common.entity.AuditLog;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.FileProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.AuditLogRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.NoSuchElementException;
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
    private final AtfmDatabaseStateGateway databaseStateGateway;
    private final ReportSnapshotStore snapshotStore;

    @Autowired
    public IncomingReportService(AuditLogRepository auditLogRepository,
                                 FileRecordRepository fileRecordRepository,
                                 PermitImportRepository permitImportRepository,
                                 VietnameseErrorMessageTranslator errorMessageTranslator,
                                 ObjectProvider<AtfmDatabaseStateGateway> gatewayProvider,
                                 ObjectProvider<ReportSnapshotStore> snapshotProvider) {
        this(auditLogRepository, fileRecordRepository, permitImportRepository, errorMessageTranslator,
                gatewayProvider.getIfAvailable(), snapshotProvider.getIfAvailable(ReportSnapshotStore::new));
    }

    public IncomingReportService(AuditLogRepository auditLogRepository,
                                 FileRecordRepository fileRecordRepository,
                                 PermitImportRepository permitImportRepository,
                                 VietnameseErrorMessageTranslator errorMessageTranslator,
                                 AtfmDatabaseStateGateway databaseStateGateway,
                                 ReportSnapshotStore snapshotStore) {
        this.auditLogRepository = auditLogRepository; this.fileRecordRepository = fileRecordRepository;
        this.permitImportRepository = permitImportRepository; this.errorMessageTranslator = errorMessageTranslator;
        this.databaseStateGateway = databaseStateGateway; this.snapshotStore = snapshotStore;
    }

    public IncomingReportService(AuditLogRepository auditLogRepository,
                                 FileRecordRepository fileRecordRepository,
                                 PermitImportRepository permitImportRepository,
                                 VietnameseErrorMessageTranslator errorMessageTranslator) {
        this(auditLogRepository, fileRecordRepository, permitImportRepository, errorMessageTranslator,
                null, new ReportSnapshotStore());
    }

    @Transactional(readOnly = true)
    public PagedResponse<EmailReportRowResponse> search(int page, int size) {
        return search(new IncomingReportFilter(null, null, null, null, null), page, size, null);
    }

    @Transactional(readOnly = true)
    public PagedResponse<EmailReportRowResponse> search(IncomingReportFilter filter, int page, int size,
                                                        String snapshotId) {
        validate(filter, page, size);
        if (snapshotId == null || snapshotId.isBlank()) {
            return searchPage(filter, page, size);
        }
        ReportSnapshotStore.Snapshot snapshot = snapshotStore.require(snapshotId, "incoming");
        int from = Math.min(page * size, snapshot.rows().size());
        int to = Math.min(from + size, snapshot.rows().size());
        int pages = snapshot.rows().isEmpty() ? 0 : (snapshot.rows().size() + size - 1) / size;
        return new PagedResponse<>(snapshot.rows().subList(from, to), page, size, snapshot.rows().size(),
                pages, page + 1 < pages, page > 0, snapshot.id());
    }

    private PagedResponse<EmailReportRowResponse> searchPage(IncomingReportFilter filter, int page, int size) {
        org.springframework.data.domain.Page<AuditLog> eventPage = auditLogRepository.findByActionStartingWith(
                INCOMING_ACTION_PREFIX, org.springframework.data.domain.PageRequest.of(page, size,
                        Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id"))));
        List<AuditLog> events = eventPage.getContent().stream()
                .filter(event -> filter.from() == null || !event.getTimestamp().isBefore(filter.from()))
                .filter(event -> filter.to() == null || !event.getTimestamp().isAfter(filter.to()))
                .toList();
        List<Long> ids = events.stream().filter(e -> e.getSyncJob() != null).map(e -> e.getSyncJob().getId()).distinct().toList();
        Map<Long, FileRecord> files = latestRecords(ids); Map<Long, PermitImport> permits = permits(ids);
        Map<Long, DatabaseState> states = databaseStates(events, permits, files);
        Map<Long, String> numbers = permitNumbers(permits);
        List<EmailReportRowResponse> rows = events.stream().map(e -> toResponse(e, files, numbers, states))
                .filter(row -> filter.processingStatus() == null || row.processingStatus() == filter.processingStatus())
                .filter(row -> matchesQuery(row, filter.query()))
                .filter(row -> filter.fileType() == null || filter.fileType().matches(row.storedFileName(), row.attachmentName()))
                .toList();
        return new PagedResponse<>(rows, page, size, eventPage.getTotalElements(), eventPage.getTotalPages(),
                eventPage.hasNext(), eventPage.hasPrevious(), null);
    }

    @Transactional(readOnly = true)
    public EmailReportSummaryResponse summarize(IncomingReportFilter filter) {
        validate(filter, 0, 25);
        ReportSnapshotStore.Snapshot snapshot = createSnapshot(filter);
        Map<String, Long> processing = enumMap(EmailProcessingStatus.values());
        snapshot.rows().forEach(row -> processing.merge(row.processingStatus().name(), 1L, Long::sum));
        Map<String, Long> acknowledgements = new LinkedHashMap<>();
        long success = countState(snapshot.rows(), DatabaseState.FOUND);
        long errors = countState(snapshot.rows(), DatabaseState.MISSING) + countState(snapshot.rows(), DatabaseState.ERROR);
        long pending = countState(snapshot.rows(), DatabaseState.PENDING);
        long unknown = countState(snapshot.rows(), DatabaseState.UNKNOWN);
        long total = snapshot.rows().size();
        return new EmailReportSummaryResponse(filter.from(), filter.to(), total, processing, acknowledgements,
                total, success, errors, pending, unknown, snapshot.createdAt(), snapshot.id());
    }

    @Transactional(readOnly = true)
    public EmailReportRowResponse get(Long id) {
        AuditLog event = auditLogRepository.findById(id)
                .filter(log -> log.getAction().startsWith(INCOMING_ACTION_PREFIX))
                .orElseThrow(() -> new NoSuchElementException("Incoming report record not found: " + id));
        List<Long> ids = event.getSyncJob() == null ? List.of() : List.of(event.getSyncJob().getId());
        Map<Long, FileRecord> files = latestRecords(ids); Map<Long, PermitImport> permits = permits(ids);
        Map<Long, DatabaseState> states = databaseStates(List.of(event), permits, files);
        return toResponse(event, files, permitNumbers(permits), states);
    }

    private ReportSnapshotStore.Snapshot createSnapshot(IncomingReportFilter filter) {
        List<AuditLog> events = auditLogRepository.findAll(
                Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id"))).stream()
                .filter(log -> log.getAction().startsWith(INCOMING_ACTION_PREFIX))
                .filter(log -> filter.from() == null || !log.getTimestamp().isBefore(filter.from()))
                .filter(log -> filter.to() == null || !log.getTimestamp().isAfter(filter.to()))
                .toList();
        List<Long> ids = events.stream().filter(e -> e.getSyncJob() != null)
                .map(e -> e.getSyncJob().getId()).distinct().toList();
        Map<Long, FileRecord> files = latestRecords(ids); Map<Long, PermitImport> permits = permits(ids);
        Map<Long, DatabaseState> states = databaseStates(events, permits, files);
        Map<Long, String> permitNumbers = permitNumbers(permits);
        List<EmailReportRowResponse> rows = events.stream()
                .map(e -> toResponse(e, files, permitNumbers, states))
                .filter(row -> filter.processingStatus() == null || row.processingStatus() == filter.processingStatus())
                .filter(row -> matchesQuery(row, filter.query()))
                .filter(row -> filter.fileType() == null || filter.fileType().matches(row.storedFileName(), row.attachmentName()))
                .toList();
        return snapshotStore.create("incoming", rows);
    }

    private EmailReportRowResponse toResponse(AuditLog event,
                                              Map<Long, FileRecord> latestRecords,
                                              Map<Long, String> permitNumbers,
                                              Map<Long, DatabaseState> states) {
        boolean duplicate = DUPLICATE_ACTION.equals(event.getAction());
        Long syncJobId = event.getSyncJob() != null ? event.getSyncJob().getId() : null;
        FileRecord record = syncJobId != null ? latestRecords.get(syncJobId) : null;
        String fileName = fileName(event.getInputSummary(), record);
        FileProcessingStatus fileStatus = duplicate
                ? FileProcessingStatus.SKIPPED
                : record != null ? record.getProcessingStatus() : FileProcessingStatus.DOWNLOADED;
        String errorMessage = duplicate
                ? errorMessageTranslator.translate(event.getOutputSummary())
                : record != null ? errorMessageTranslator.translate(record.getErrorMessage()) : null;
        SyncStatus jobStatus = duplicate
                ? SyncStatus.SKIPPED
                : event.getSyncJob() != null ? event.getSyncJob().getStatus() : event.getResultStatus();

        DatabaseState databaseState = syncJobId == null ? DatabaseState.UNKNOWN
                : states.getOrDefault(syncJobId, DatabaseState.UNKNOWN);
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
                jobStatus,
                record != null && WordFormatRequirement.isRequired(
                        record.getOriginalFileName(), record.getErrorMessage()),
                "folder",
                2,
                databaseState,
                databaseState == DatabaseState.FOUND);
    }

    private Map<Long, PermitImport> permits(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, PermitImport> result = new HashMap<>();
        permitImportRepository.findBySyncJobIdIn(ids).forEach(p -> result.put(p.getSyncJob().getId(), p));
        return result;
    }

    private Map<Long, String> permitNumbers(Map<Long, PermitImport> permits) {
        Map<Long, String> result = new HashMap<>(); permits.forEach((id, p) -> result.put(id, p.getNormalizedPermitId()));
        return result;
    }

    private Map<Long, DatabaseState> databaseStates(List<AuditLog> events, Map<Long, PermitImport> permits, Map<Long, FileRecord> files) {
        if (databaseStateGateway == null) return Map.of();
        return databaseStateGateway.states(events.stream().filter(e -> e.getSyncJob() != null).map(e -> {
            Long id = e.getSyncJob().getId(); PermitImport p = permits.get(id);
            FileRecord file = files.get(id);
            FileProcessingStatus status = file == null ? FileProcessingStatus.DOWNLOADED : file.getProcessingStatus();
            return new AtfmDatabaseStateGateway.Candidate(id, p == null ? null : p.getTargetMasterId(),
                    p == null ? null : p.getTargetPermId(), p == null ? null : p.getNormalizedPermitId(),
                    isComplete(status), status == FileProcessingStatus.FAILED || status == FileProcessingStatus.QUARANTINED);
        }).distinct().toList());
    }

    private boolean matchesQuery(EmailReportRowResponse row, String query) {
        if (query == null || query.isBlank()) return true; String value = query.trim().toLowerCase(Locale.ROOT);
        return contains(row.subject(), value) || contains(row.sender(), value) || contains(row.errorMessage(), value);
    }
    private boolean contains(String source, String value) { return source != null && source.toLowerCase(Locale.ROOT).contains(value); }
    private long countState(List<EmailReportRowResponse> rows, DatabaseState state) {
        return rows.stream().filter(row -> row.databaseState() == state).count();
    }
    private Map<String, Long> enumMap(Enum<?>[] values) {
        Map<String, Long> result = new LinkedHashMap<>(); for (Enum<?> value : values) result.put(value.name(), 0L); return result;
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

    private void validate(IncomingReportFilter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to()))
            throw new IllegalArgumentException("'from' must be before or equal to 'to'");
        if (page < 0) throw new IllegalArgumentException("'page' must be greater than or equal to 0");
        if (size < 1 || size > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("'size' must be between 1 and " + MAX_PAGE_SIZE);
    }
}
