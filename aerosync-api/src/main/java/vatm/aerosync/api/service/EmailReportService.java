package vatm.aerosync.api.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.EmailReportDetailResponse;
import vatm.aerosync.api.dto.EmailReportRowResponse;
import vatm.aerosync.api.dto.EmailReportSummaryResponse;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.common.entity.EmailMetadata;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.SyncJob;
import vatm.aerosync.common.enums.EmailAcknowledgementStatus;
import vatm.aerosync.common.enums.EmailProcessingStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.EmailMetadataRepository;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class EmailReportService {

    static final int MAX_PAGE_SIZE = 100;

    private final EmailMetadataRepository emailMetadataRepository;
    private final PermitImportRepository permitImportRepository;
    private final FileRecordRepository fileRecordRepository;
    private final VietnameseErrorMessageTranslator errorMessageTranslator;
    private final AtfmDatabaseStateGateway databaseStateGateway;
    private final ReportSnapshotStore snapshotStore;

    @Autowired
    public EmailReportService(EmailMetadataRepository emailMetadataRepository,
                              PermitImportRepository permitImportRepository,
                              FileRecordRepository fileRecordRepository,
                              VietnameseErrorMessageTranslator errorMessageTranslator,
                              ObjectProvider<AtfmDatabaseStateGateway> gatewayProvider,
                              ObjectProvider<ReportSnapshotStore> snapshotProvider) {
        this(emailMetadataRepository, permitImportRepository, fileRecordRepository,
                errorMessageTranslator, gatewayProvider.getIfAvailable(),
                snapshotProvider.getIfAvailable(ReportSnapshotStore::new));
    }

    public EmailReportService(EmailMetadataRepository emailMetadataRepository,
                              PermitImportRepository permitImportRepository,
                              FileRecordRepository fileRecordRepository,
                              VietnameseErrorMessageTranslator errorMessageTranslator,
                              AtfmDatabaseStateGateway databaseStateGateway,
                              ReportSnapshotStore snapshotStore) {
        this.emailMetadataRepository = emailMetadataRepository;
        this.permitImportRepository = permitImportRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.errorMessageTranslator = errorMessageTranslator;
        this.databaseStateGateway = databaseStateGateway;
        this.snapshotStore = snapshotStore;
    }

    public EmailReportService(EmailMetadataRepository emailMetadataRepository,
                              PermitImportRepository permitImportRepository,
                              FileRecordRepository fileRecordRepository,
                              VietnameseErrorMessageTranslator errorMessageTranslator) {
        this(emailMetadataRepository, permitImportRepository, fileRecordRepository,
                errorMessageTranslator, null, new ReportSnapshotStore());
    }

    @Transactional(readOnly = true)
    public PagedResponse<EmailReportRowResponse> search(EmailReportFilter filter, int page, int size) {
        return search(filter, page, size, null);
    }

    @Transactional(readOnly = true)
    public PagedResponse<EmailReportRowResponse> search(EmailReportFilter filter, int page, int size,
                                                        String snapshotId) {
        validateRange(filter.from(), filter.to()); validatePage(page, size);
        ReportSnapshotStore.Snapshot snapshot = snapshotId == null || snapshotId.isBlank()
                ? createSnapshot(filter) : snapshotStore.require(snapshotId, "email");
        return page(snapshot, page, size);
    }

    private ReportSnapshotStore.Snapshot createSnapshot(EmailReportFilter filter) {
        if (snapshotStore == null) throw new IllegalStateException("Report snapshot store is unavailable");
        List<EmailMetadata> metadata = emailMetadataRepository.findAll(toSpecification(filter),
                Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id"))).stream()
                .filter(item -> matchesQuery(item, filter.query())).toList();
        Map<Long, PermitImport> permits = permitsFor(metadata);
        Map<Long, FileRecord> files = latestFileRecordsFor(metadata);
        Map<Long, DatabaseState> states = databaseStates(metadata, permits);
        List<EmailReportRowResponse> rows = metadata.stream()
                .map(item -> toRowResponse(item, permits, files, states))
                .filter(row -> filter.fileType() == null
                        || filter.fileType().matches(row.storedFileName(), row.attachmentName()))
                .toList();
        return snapshotStore.create("email", rows);
    }

    private PagedResponse<EmailReportRowResponse> page(ReportSnapshotStore.Snapshot snapshot, int page, int size) {
        int from = Math.min(page * size, snapshot.rows().size());
        int to = Math.min(from + size, snapshot.rows().size());
        int pages = snapshot.rows().isEmpty() ? 0 : (snapshot.rows().size() + size - 1) / size;
        return new PagedResponse<>(snapshot.rows().subList(from, to), page, size, snapshot.rows().size(),
                pages, page + 1 < pages, page > 0, snapshot.id());
    }

    @Transactional(readOnly = true)
    public EmailReportDetailResponse get(Long id) {
        EmailMetadata metadata = emailMetadataRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Email report record not found: " + id));
        SyncJob job = metadata.getSyncJob();
        String permitNumber = job == null
                ? null
                : permitImportRepository.findBySyncJobId(job.getId())
                        .map(PermitImport::getNormalizedPermitId)
                        .orElse(null);
        java.util.Optional<FileRecord> latestFileRecord = job == null
                ? java.util.Optional.empty()
                : latestFileRecord(fileRecordRepository.findBySyncJobId(job.getId()));
        return new EmailReportDetailResponse(
                metadata.getId(),
                job != null ? job.getId() : null,
                permitNumber,
                metadata.getMessageId(),
                metadata.getMailboxFolder(),
                metadata.getUidValidity(),
                metadata.getMessageUid(),
                metadata.getSender(),
                metadata.getSubject(),
                metadata.getReceivedAt(),
                metadata.getAttachmentCount(),
                metadata.getAttachmentIndex(),
                metadata.getAttachmentName(),
                latestFileRecord.map(StoredFileName::from).orElse(null),
                latestFileRecord.map(FileRecord::getErrorMessage)
                        .map(errorMessageTranslator::translate)
                        .orElse(null),
                metadata.getBody(),
                metadata.getProcessingStatus(),
                metadata.getAcknowledgementStatus(),
                metadata.isIngestComplete(),
                metadata.getAcknowledgedAt(),
                errorMessageTranslator.translate(metadata.getAcknowledgementError()),
                job != null ? job.getStatus() : null,
                "email",
                1);
    }

    @Transactional(readOnly = true)
    public EmailReportSummaryResponse summarize(LocalDateTime from, LocalDateTime to) {
        return summarize(new EmailReportFilter(from, to, null, null, null, null, null, null));
    }

    @Transactional(readOnly = true)
    public EmailReportSummaryResponse summarize(EmailReportFilter filter) {
        validateRange(filter.from(), filter.to());
        ReportSnapshotStore.Snapshot snapshot = createSnapshot(filter);
        Map<String, Long> processing = enumMap(EmailProcessingStatus.values());
        Map<String, Long> acknowledgements = enumMap(EmailAcknowledgementStatus.values());
        for (EmailReportRowResponse row : snapshot.rows()) {
            if (row.processingStatus() != null) processing.merge(row.processingStatus().name(), 1L, Long::sum);
            if (row.acknowledgementStatus() != null) acknowledgements.merge(row.acknowledgementStatus().name(), 1L, Long::sum);
        }
        long success = countState(snapshot.rows(), DatabaseState.FOUND);
        long errors = countState(snapshot.rows(), DatabaseState.MISSING) + countState(snapshot.rows(), DatabaseState.ERROR);
        long pending = countState(snapshot.rows(), DatabaseState.PENDING);
        long unknown = countState(snapshot.rows(), DatabaseState.UNKNOWN);
        long total = snapshot.rows().size();
        return new EmailReportSummaryResponse(filter.from(), filter.to(), total, processing, acknowledgements,
                total, success, errors, pending, unknown, snapshot.createdAt(), snapshot.id());
    }

    private long countState(List<EmailReportRowResponse> rows, DatabaseState state) {
        return rows.stream().filter(row -> row.databaseState() == state).count();
    }

    private Specification<EmailMetadata> toSpecification(EmailReportFilter filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.from() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("receivedAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("receivedAt"), filter.to()));
            }
            if (filter.processingStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("processingStatus"), filter.processingStatus()));
            }
            if (filter.acknowledgementStatus() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("acknowledgementStatus"), filter.acknowledgementStatus()));
            }
            if (filter.jobStatus() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.join("syncJob", JoinType.LEFT).get("status"), filter.jobStatus()));
            }
            if (hasText(filter.sender())) {
                predicates.add(containsIgnoreCase(
                        criteriaBuilder, root.get("sender"), filter.sender().trim()));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private boolean matchesQuery(EmailMetadata metadata, String query) {
        if (!hasText(query)) return true;
        String value = query.trim().toLowerCase(Locale.ROOT);
        return contains(metadata.getMessageId(), value) || contains(metadata.getSender(), value)
                || contains(metadata.getSubject(), value) || contains(metadata.getBody(), value)
                || contains(metadata.getAttachmentName(), value);
    }

    private boolean contains(String source, String lowerQuery) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private Predicate containsIgnoreCase(jakarta.persistence.criteria.CriteriaBuilder criteriaBuilder,
                                         jakarta.persistence.criteria.Path<String> path,
                                         String value) {
        String pattern = "%" + escapeLike(value.toLowerCase(Locale.ROOT)) + "%";
        return criteriaBuilder.like(criteriaBuilder.lower(path), pattern, '\\');
    }

    private EmailReportRowResponse toRowResponse(EmailMetadata metadata,
                                                 Map<Long, PermitImport> permits,
                                                 Map<Long, FileRecord> latestFileRecords,
                                                 Map<Long, DatabaseState> states) {
        SyncJob job = metadata.getSyncJob();
        Long syncJobId = job != null ? job.getId() : null;
        FileRecord latestFileRecord = syncJobId != null ? latestFileRecords.get(syncJobId) : null;
        DatabaseState databaseState = syncJobId == null ? DatabaseState.UNKNOWN
                : states.getOrDefault(syncJobId, DatabaseState.UNKNOWN);
        return new EmailReportRowResponse(
                metadata.getId(),
                syncJobId,
                syncJobId != null && permits.containsKey(syncJobId) ? permits.get(syncJobId).getNormalizedPermitId() : null,
                metadata.getMessageId(),
                metadata.getSender(),
                metadata.getSubject(),
                metadata.getReceivedAt(),
                metadata.getAttachmentCount(),
                metadata.getAttachmentIndex(),
                metadata.getAttachmentName(),
                StoredFileName.from(latestFileRecord),
                latestFileRecord != null
                        ? errorMessageTranslator.translate(latestFileRecord.getErrorMessage())
                        : null,
                metadata.getProcessingStatus(),
                metadata.getAcknowledgementStatus(),
                metadata.isIngestComplete(),
                metadata.getAcknowledgedAt(),
                job != null ? job.getStatus() : null,
                latestFileRecord != null && WordFormatRequirement.isRequired(
                        latestFileRecord.getOriginalFileName(), latestFileRecord.getErrorMessage()),
                "email",
                1,
                databaseState,
                databaseState == DatabaseState.FOUND);
    }

    private Map<Long, PermitImport> permitsFor(List<EmailMetadata> metadataRows) {
        List<Long> ids = syncJobIds(metadataRows);
        if (ids.isEmpty()) return Map.of();
        Map<Long, PermitImport> result = new java.util.HashMap<>();
        permitImportRepository.findBySyncJobIdIn(ids).forEach(p -> result.put(p.getSyncJob().getId(), p));
        return result;
    }

    private List<Long> syncJobIds(List<EmailMetadata> rows) {
        return rows.stream().map(EmailMetadata::getSyncJob).filter(java.util.Objects::nonNull)
                .map(SyncJob::getId).distinct().toList();
    }

    private Map<Long, DatabaseState> databaseStates(List<EmailMetadata> rows, Map<Long, PermitImport> permits) {
        if (databaseStateGateway == null) return Map.of();
        return databaseStateGateway.states(rows.stream().filter(m -> m.getSyncJob() != null).map(m -> {
            PermitImport p = permits.get(m.getSyncJob().getId());
            EmailProcessingStatus status = m.getProcessingStatus();
            boolean terminal = status != null && status != EmailProcessingStatus.DISCOVERED
                    && status != EmailProcessingStatus.DOWNLOADED && status != EmailProcessingStatus.PROCESSING;
            boolean error = status == EmailProcessingStatus.FAILED || status == EmailProcessingStatus.QUARANTINED;
            return new AtfmDatabaseStateGateway.Candidate(m.getSyncJob().getId(),
                    p == null ? null : p.getTargetMasterId(), p == null ? null : p.getTargetPermId(),
                    p == null ? null : p.getNormalizedPermitId(), terminal, error);
        }).distinct().toList());
    }

    private Map<Long, FileRecord> latestFileRecordsFor(List<EmailMetadata> metadataRows) {
        List<Long> syncJobIds = metadataRows.stream()
                .map(EmailMetadata::getSyncJob)
                .filter(java.util.Objects::nonNull)
                .map(SyncJob::getId)
                .distinct()
                .toList();
        if (syncJobIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, FileRecord> latestRecords = new java.util.HashMap<>();
        for (FileRecord record : fileRecordRepository.findBySyncJobIdIn(syncJobIds)) {
            Long syncJobId = record.getSyncJob().getId();
            latestRecords.merge(syncJobId, record, this::newerRecord);
        }
        return latestRecords;
    }

    private java.util.Optional<FileRecord> latestFileRecord(List<FileRecord> records) {
        return records.stream().max(this::compareRecords);
    }

    private FileRecord newerRecord(FileRecord first, FileRecord second) {
        return compareRecords(first, second) >= 0 ? first : second;
    }

    private int compareRecords(FileRecord first, FileRecord second) {
        Comparator<LocalDateTime> timestamps = Comparator.nullsFirst(Comparator.naturalOrder());
        int timestampResult = timestamps.compare(first.getCreatedAt(), second.getCreatedAt());
        if (timestampResult != 0) {
            return timestampResult;
        }
        return Comparator.<Long>nullsFirst(Comparator.naturalOrder())
                .compare(first.getId(), second.getId());
    }

    private void validateRange(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be before or equal to 'to'");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("'page' must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("'size' must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private Map<String, Long> enumMap(Enum<?>[] values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Enum<?> value : values) {
            counts.put(value.name(), 0L);
        }
        return counts;
    }
}
