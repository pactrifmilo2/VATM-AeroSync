package vatm.aerosync.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.dto.FileIngestedEvent;
import vatm.aerosync.common.dto.PermitReviewPublishCommand;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.enums.FileSourceType;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.enums.SyncStatus;
import vatm.aerosync.common.repository.PermitReviewRepository;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.SchedulePermit;
import vatm.aerosync.worker.pipeline.AircraftTypeResolutionStep;
import vatm.aerosync.worker.pipeline.BusinessRuleValidatorStep;
import vatm.aerosync.worker.pipeline.NormalizerStep;
import vatm.aerosync.worker.pipeline.ViaResolutionStep;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

@Service
public class PermitReviewPublishingService {

    private final PermitReviewRepository permitReviewRepository;
    private final PermitReviewSnapshotMapper snapshotMapper;
    private final NormalizerStep normalizerStep;
    private final AircraftTypeResolutionStep aircraftTypeResolutionStep;
    private final ViaResolutionStep viaResolutionStep;
    private final BusinessRuleValidatorStep businessRuleValidatorStep;
    private final PermitImportCoordinator permitImportCoordinator;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public PermitReviewPublishingService(PermitReviewRepository permitReviewRepository,
                                         PermitReviewSnapshotMapper snapshotMapper,
                                         NormalizerStep normalizerStep,
                                         AircraftTypeResolutionStep aircraftTypeResolutionStep,
                                         ViaResolutionStep viaResolutionStep,
                                         BusinessRuleValidatorStep businessRuleValidatorStep,
                                         PermitImportCoordinator permitImportCoordinator,
                                         AuditLogService auditLogService,
                                         ObjectMapper objectMapper) {
        this.permitReviewRepository = permitReviewRepository;
        this.snapshotMapper = snapshotMapper;
        this.normalizerStep = normalizerStep;
        this.aircraftTypeResolutionStep = aircraftTypeResolutionStep;
        this.viaResolutionStep = viaResolutionStep;
        this.businessRuleValidatorStep = businessRuleValidatorStep;
        this.permitImportCoordinator = permitImportCoordinator;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void publish(PermitReviewPublishCommand command) {
        PermitReview review = findForUpdate(command.reviewId());
        if (review.getStatus() == PermitReviewStatus.PUBLISHED) {
            return;
        }
        if (review.getStatus() != PermitReviewStatus.PUBLISHING) {
            throw new IllegalStateException(
                    "Permit review %d is %s, not PUBLISHING"
                            .formatted(review.getId(), review.getStatus()));
        }

        PermitImport permitImport = review.getPermitImport();
        PermitReviewSnapshot selected = readSnapshot(
                hasText(review.getCorrectedPermitJson())
                        ? review.getCorrectedPermitJson()
                        : review.getOriginalPermitJson());
        SchedulePermit permit = snapshotMapper.toPermit(selected);
        ProcessingContext context = new ProcessingContext(new FileIngestedEvent(
                permitImport.getSyncJob().getId(),
                "",
                permitImport.getSourceFileHash(),
                FileSourceType.FILESYSTEM,
                false));
        context.setSchedulePermit(permit);

        normalizerStep.normalize(context);
        aircraftTypeResolutionStep.resolve(context);
        viaResolutionStep.resolve(context);
        businessRuleValidatorStep.validate(context);

        PermitImportOutcome outcome = permitImportCoordinator.publishApproved(
                permitImport, context.getSchedulePermit());
        review.setStatus(PermitReviewStatus.PUBLISHED);
        review.setPublishedBy(command.requestedBy());
        review.setPublishedAt(LocalDateTime.now());
        review.setPublishedPermitJson(writeSnapshot(
                snapshotMapper.toSnapshot(context.getSchedulePermit())));
        review.setPublishError(null);
        permitReviewRepository.save(review);
        auditLogService.record(
                permitImport.getSyncJob().getId(),
                "PERMIT_REVIEW_PUBLISH",
                "reviewId=%d, requestedBy=%s".formatted(review.getId(), command.requestedBy()),
                "status=%s, targetMasterId=%s, targetPermId=%s".formatted(
                        outcome.status(), outcome.targetMasterId(), outcome.targetPermId()),
                SyncStatus.SUCCESS,
                0);
    }

    @Transactional
    public void markFailed(PermitReviewPublishCommand command, String message) {
        PermitReview review = findForUpdate(command.reviewId());
        if (review.getStatus() != PermitReviewStatus.PUBLISHING) {
            return;
        }
        review.setStatus(PermitReviewStatus.PUBLISH_FAILED);
        review.setPublishError(truncate(message));
        permitReviewRepository.save(review);
        auditLogService.record(
                review.getPermitImport().getSyncJob().getId(),
                "PERMIT_REVIEW_PUBLISH",
                "reviewId=%d, requestedBy=%s".formatted(review.getId(), command.requestedBy()),
                review.getPublishError(),
                SyncStatus.FAILED,
                0);
    }

    private PermitReview findForUpdate(Long id) {
        return permitReviewRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Permit review not found: " + id));
    }

    private PermitReviewSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, PermitReviewSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored permit review snapshot is invalid", exception);
        }
    }

    private String writeSnapshot(PermitReviewSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize published permit snapshot", exception);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 2000) {
            return message;
        }
        return message.substring(0, 2000);
    }
}
