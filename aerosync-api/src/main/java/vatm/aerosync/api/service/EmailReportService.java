package vatm.aerosync.api.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
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

    public EmailReportService(EmailMetadataRepository emailMetadataRepository,
                              PermitImportRepository permitImportRepository,
                              FileRecordRepository fileRecordRepository) {
        this.emailMetadataRepository = emailMetadataRepository;
        this.permitImportRepository = permitImportRepository;
        this.fileRecordRepository = fileRecordRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<EmailReportRowResponse> search(EmailReportFilter filter, int page, int size) {
        validateRange(filter.from(), filter.to());
        validatePage(page, size);

        PageRequest pageRequest = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id")));
        Page<EmailMetadata> metadataPage = emailMetadataRepository
                .findAll(toSpecification(filter), pageRequest);
        Map<Long, String> permitNumbers = permitNumbersFor(metadataPage.getContent());
        Map<Long, String> storedFileNames = storedFileNamesFor(metadataPage.getContent());
        Page<EmailReportRowResponse> result = metadataPage
                .map(metadata -> toRowResponse(metadata, permitNumbers, storedFileNames));
        return PagedResponse.from(result);
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
        String storedFileName = job == null
                ? null
                : latestFileRecord(fileRecordRepository.findBySyncJobId(job.getId()))
                        .map(StoredFileName::from)
                        .orElse(null);
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
                storedFileName,
                metadata.getBody(),
                metadata.getProcessingStatus(),
                metadata.getAcknowledgementStatus(),
                metadata.isIngestComplete(),
                metadata.getAcknowledgedAt(),
                metadata.getAcknowledgementError(),
                job != null ? job.getStatus() : null);
    }

    @Transactional(readOnly = true)
    public EmailReportSummaryResponse summarize(LocalDateTime from, LocalDateTime to) {
        validateRange(from, to);

        Map<String, Long> processingCounts = enumMap(EmailProcessingStatus.values());
        emailMetadataRepository.countByProcessingStatus(from, to)
                .forEach(row -> processingCounts.put(row.getStatus().name(), row.getTotal()));

        Map<String, Long> acknowledgementCounts = enumMap(EmailAcknowledgementStatus.values());
        emailMetadataRepository.countByAcknowledgementStatus(from, to)
                .forEach(row -> acknowledgementCounts.put(row.getStatus().name(), row.getTotal()));

        long total = processingCounts.values().stream().mapToLong(Long::longValue).sum();
        return new EmailReportSummaryResponse(from, to, total, processingCounts, acknowledgementCounts);
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
            if (hasText(filter.query())) {
                String value = filter.query().trim();
                predicates.add(criteriaBuilder.or(
                        containsIgnoreCase(criteriaBuilder, root.get("messageId"), value),
                        containsIgnoreCase(criteriaBuilder, root.get("sender"), value),
                        containsIgnoreCase(criteriaBuilder, root.get("subject"), value),
                        containsIgnoreCase(criteriaBuilder, root.get("attachmentName"), value)));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Predicate containsIgnoreCase(jakarta.persistence.criteria.CriteriaBuilder criteriaBuilder,
                                         jakarta.persistence.criteria.Path<String> path,
                                         String value) {
        String pattern = "%" + escapeLike(value.toLowerCase(Locale.ROOT)) + "%";
        return criteriaBuilder.like(criteriaBuilder.lower(path), pattern, '\\');
    }

    private EmailReportRowResponse toRowResponse(EmailMetadata metadata,
                                                 Map<Long, String> permitNumbers,
                                                 Map<Long, String> storedFileNames) {
        SyncJob job = metadata.getSyncJob();
        Long syncJobId = job != null ? job.getId() : null;
        return new EmailReportRowResponse(
                metadata.getId(),
                syncJobId,
                syncJobId != null ? permitNumbers.get(syncJobId) : null,
                metadata.getMessageId(),
                metadata.getSender(),
                metadata.getSubject(),
                metadata.getReceivedAt(),
                metadata.getAttachmentCount(),
                metadata.getAttachmentIndex(),
                metadata.getAttachmentName(),
                syncJobId != null ? storedFileNames.get(syncJobId) : null,
                metadata.getProcessingStatus(),
                metadata.getAcknowledgementStatus(),
                metadata.isIngestComplete(),
                metadata.getAcknowledgedAt(),
                job != null ? job.getStatus() : null);
    }

    private Map<Long, String> permitNumbersFor(List<EmailMetadata> metadataRows) {
        List<Long> syncJobIds = metadataRows.stream()
                .map(EmailMetadata::getSyncJob)
                .filter(java.util.Objects::nonNull)
                .map(SyncJob::getId)
                .distinct()
                .toList();
        if (syncJobIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> permitNumbers = new java.util.HashMap<>();
        permitImportRepository.findBySyncJobIdIn(syncJobIds)
                .forEach(permitImport -> permitNumbers.put(
                        permitImport.getSyncJob().getId(),
                        permitImport.getNormalizedPermitId()));
        return permitNumbers;
    }

    private Map<Long, String> storedFileNamesFor(List<EmailMetadata> metadataRows) {
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
        Map<Long, String> storedFileNames = new java.util.HashMap<>();
        latestRecords.forEach((syncJobId, record) ->
                storedFileNames.put(syncJobId, StoredFileName.from(record)));
        return storedFileNames;
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
