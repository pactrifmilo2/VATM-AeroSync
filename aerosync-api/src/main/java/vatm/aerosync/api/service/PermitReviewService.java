package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitFieldDiagnosticResponse;
import vatm.aerosync.api.dto.PermitParseWarningResponse;
import vatm.aerosync.api.dto.PermitProfileCandidateResponse;
import vatm.aerosync.api.dto.PermitReviewDetailResponse;
import vatm.aerosync.api.dto.PermitReviewSummaryResponse;
import vatm.aerosync.common.dto.PermitReviewPublishCommand;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.repository.PermitReviewRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class PermitReviewService {

    static final int MAX_PAGE_SIZE = 100;

    private final PermitReviewRepository permitReviewRepository;
    private final PermitReviewCommandPublisher commandPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TransactionTemplate transactionTemplate;

    public PermitReviewService(PermitReviewRepository permitReviewRepository,
                               PermitReviewCommandPublisher commandPublisher,
                               PlatformTransactionManager transactionManager) {
        this.permitReviewRepository = permitReviewRepository;
        this.commandPublisher = commandPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PagedResponse<PermitReviewSummaryResponse> list(PermitReviewStatus status,
                                                           int page,
                                                           int size) {
        validatePage(page, size);
        PageRequest request = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<PermitReview> reviews = status == null
                ? permitReviewRepository.findAll(request)
                : permitReviewRepository.findByStatus(status, request);
        return PagedResponse.from(reviews.map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public PermitReviewDetailResponse get(Long id) {
        return toDetail(find(id));
    }

    @Transactional
    public PermitReviewDetailResponse correct(Long id,
                                              PermitReviewSnapshot snapshot,
                                              String comment,
                                              String actor) {
        PermitReview review = findForUpdate(id);
        requireStatus(review, Set.of(PermitReviewStatus.PENDING, PermitReviewStatus.CORRECTED),
                "Only pending or corrected reviews can be edited");
        validateSnapshot(snapshot);
        review.setCorrectedPermitJson(writeJson(snapshot, "corrected permit"));
        review.setCorrectionComment(blankToNull(comment));
        review.setCorrectedBy(actor);
        review.setCorrectedAt(LocalDateTime.now());
        review.setStatus(PermitReviewStatus.CORRECTED);
        review.setPublishError(null);
        return toDetail(permitReviewRepository.save(review));
    }

    @Transactional
    public PermitReviewDetailResponse approve(Long id,
                                              String comment,
                                              String actor) {
        PermitReview review = findForUpdate(id);
        requireStatus(review, Set.of(PermitReviewStatus.PENDING, PermitReviewStatus.CORRECTED),
                "Only pending or corrected reviews can be approved");
        validateSnapshot(selectedSnapshot(review));
        review.setApprovalComment(blankToNull(comment));
        review.setApprovedBy(actor);
        review.setApprovedAt(LocalDateTime.now());
        review.setStatus(PermitReviewStatus.APPROVED);
        review.setRejectionReason(null);
        review.setRejectedBy(null);
        review.setRejectedAt(null);
        review.setPublishError(null);
        return toDetail(permitReviewRepository.save(review));
    }

    @Transactional
    public PermitReviewDetailResponse reject(Long id,
                                             String reason,
                                             String actor) {
        PermitReview review = findForUpdate(id);
        requireStatus(review, Set.of(
                        PermitReviewStatus.PENDING,
                        PermitReviewStatus.CORRECTED,
                        PermitReviewStatus.APPROVED,
                        PermitReviewStatus.PUBLISH_FAILED),
                "This review can no longer be rejected");
        review.setRejectionReason(reason.trim());
        review.setRejectedBy(actor);
        review.setRejectedAt(LocalDateTime.now());
        review.setStatus(PermitReviewStatus.REJECTED);
        review.setPublishError(null);
        return toDetail(permitReviewRepository.save(review));
    }

    public PermitReviewDetailResponse requestPublish(Long id, String actor) {
        PublishTransition transition = transactionTemplate.execute(status -> {
            PermitReview review = findForUpdate(id);
            requireStatus(review, Set.of(
                            PermitReviewStatus.APPROVED,
                            PermitReviewStatus.PUBLISH_FAILED),
                    "Only approved reviews can be published");
            LocalDateTime requestedAt = LocalDateTime.now();
            review.setStatus(PermitReviewStatus.PUBLISHING);
            review.setPublishRequestedBy(actor);
            review.setPublishRequestedAt(requestedAt);
            review.setPublishError(null);
            PermitReview saved = permitReviewRepository.saveAndFlush(review);
            return new PublishTransition(
                    toDetail(saved),
                    new PermitReviewPublishCommand(saved.getId(), actor, requestedAt));
        });
        if (transition == null) {
            throw new IllegalStateException("Permit review publish transition returned no result");
        }
        try {
            commandPublisher.publish(transition.command());
        } catch (RuntimeException exception) {
            markDispatchFailed(id, exception.getMessage());
            throw new IllegalStateException("Failed to queue permit review publication", exception);
        }
        return transition.response();
    }

    private void markDispatchFailed(Long id, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            PermitReview review = findForUpdate(id);
            if (review.getStatus() == PermitReviewStatus.PUBLISHING) {
                review.setStatus(PermitReviewStatus.PUBLISH_FAILED);
                review.setPublishError(truncate(message));
                permitReviewRepository.save(review);
            }
        });
    }

    private PermitReview find(Long id) {
        return permitReviewRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Permit review not found: " + id));
    }

    private PermitReview findForUpdate(Long id) {
        return permitReviewRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Permit review not found: " + id));
    }

    private void requireStatus(PermitReview review,
                               Set<PermitReviewStatus> allowed,
                               String message) {
        if (!allowed.contains(review.getStatus())) {
            throw new IllegalStateException(
                    message + "; current status is " + review.getStatus());
        }
    }

    private PermitReviewSnapshot selectedSnapshot(PermitReview review) {
        String json = hasText(review.getCorrectedPermitJson())
                ? review.getCorrectedPermitJson()
                : review.getOriginalPermitJson();
        return readJson(json, PermitReviewSnapshot.class, "permit snapshot");
    }

    private void validateSnapshot(PermitReviewSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Permit snapshot is required");
        }
        if (!hasText(snapshot.normalizedPermitId())
                || snapshot.normalizedPermitId().length() > 100) {
            throw new IllegalArgumentException("A valid normalizedPermitId is required");
        }
        if (snapshot.permitDate() == null) {
            throw new IllegalArgumentException("permitDate is required");
        }
        if (snapshot.operatorId() == null
                || !snapshot.operatorId().matches("^[A-Z0-9]{3}$")) {
            throw new IllegalArgumentException("operatorId must be a three-character ICAO code");
        }
        if (!hasText(snapshot.permitType()) || !hasText(snapshot.flightType())) {
            throw new IllegalArgumentException("permitType and flightType are required");
        }
        if (snapshot.flights().isEmpty()) {
            throw new IllegalArgumentException("At least one schedule flight is required");
        }
    }

    private PermitReviewSummaryResponse toSummary(PermitReview review) {
        PermitImport permitImport = review.getPermitImport();
        return new PermitReviewSummaryResponse(
                review.getId(),
                permitImport.getSyncJob().getId(),
                permitImport.getId(),
                permitImport.getNormalizedPermitId(),
                review.getStatus(),
                review.getProfileId(),
                review.getProfileVersion(),
                review.getConfidence(),
                review.getReviewReason(),
                review.getCorrectedBy(),
                review.getCorrectedAt(),
                review.getApprovedBy(),
                review.getApprovedAt(),
                review.getPublishRequestedBy(),
                review.getPublishRequestedAt(),
                review.getPublishedBy(),
                review.getPublishedAt(),
                review.getPublishError(),
                review.getVersion(),
                review.getCreatedAt(),
                review.getUpdatedAt());
    }

    private PermitReviewDetailResponse toDetail(PermitReview review) {
        PermitImport permitImport = review.getPermitImport();
        return new PermitReviewDetailResponse(
                review.getId(),
                permitImport.getSyncJob().getId(),
                permitImport.getId(),
                permitImport.getNormalizedPermitId(),
                review.getStatus(),
                review.getProfileId(),
                review.getProfileVersion(),
                review.getConfidence(),
                review.getRunnerUpMargin(),
                review.getReviewReason(),
                readNullable(review.getOriginalPermitJson(), PermitReviewSnapshot.class, "original permit"),
                readNullable(review.getCorrectedPermitJson(), PermitReviewSnapshot.class, "corrected permit"),
                readNullable(review.getPublishedPermitJson(), PermitReviewSnapshot.class, "published permit"),
                readList(review.getProfileCandidatesJson(), PermitProfileCandidateResponse.class, "profile candidates"),
                readList(review.getFieldDiagnosticsJson(), PermitFieldDiagnosticResponse.class, "field diagnostics"),
                readList(review.getWarningsJson(), PermitParseWarningResponse.class, "parse warnings"),
                review.getCorrectionComment(),
                review.getCorrectedBy(),
                review.getCorrectedAt(),
                review.getApprovalComment(),
                review.getApprovedBy(),
                review.getApprovedAt(),
                review.getRejectionReason(),
                review.getRejectedBy(),
                review.getRejectedAt(),
                review.getPublishRequestedBy(),
                review.getPublishRequestedAt(),
                review.getPublishedBy(),
                review.getPublishedAt(),
                review.getPublishError(),
                review.getVersion(),
                review.getCreatedAt(),
                review.getUpdatedAt());
    }

    private <T> T readNullable(String json, Class<T> type, String label) {
        return hasText(json) ? readJson(json, type, label) : null;
    }

    private <T> T readJson(String json, Class<T> type, String label) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored " + label + " is invalid", exception);
        }
    }

    private <T> List<T> readList(String json, Class<T> itemType, String label) {
        if (!hasText(json)) {
            return List.of();
        }
        JavaType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, itemType);
        try {
            return objectMapper.readValue(json, listType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored " + label + " are invalid", exception);
        }
    }

    private String writeJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize " + label, exception);
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

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }

    private record PublishTransition(
            PermitReviewDetailResponse response,
            PermitReviewPublishCommand command
    ) {
    }
}
