package vatm.aerosync.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.repository.PermitReviewRepository;
import vatm.aerosync.worker.model.ProcessingContext;
import vatm.aerosync.worker.model.WordPermitParseResult;

@Service
public class PermitReviewCaptureService {

    private final PermitReviewRepository permitReviewRepository;
    private final PermitReviewSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;

    public PermitReviewCaptureService(PermitReviewRepository permitReviewRepository,
                                      PermitReviewSnapshotMapper snapshotMapper,
                                      ObjectMapper objectMapper) {
        this.permitReviewRepository = permitReviewRepository;
        this.snapshotMapper = snapshotMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PermitReview capture(PermitImport permitImport,
                                ProcessingContext context,
                                String reason) {
        PermitReview review = permitImport.getId() == null
                ? new PermitReview()
                : permitReviewRepository.findByPermitImportId(permitImport.getId())
                        .orElseGet(PermitReview::new);
        if (review.getId() != null && review.getStatus() != PermitReviewStatus.PENDING) {
            return review;
        }
        if (review.getPermitImport() == null) {
            review.setPermitImport(permitImport);
        }
        review.setStatus(PermitReviewStatus.PENDING);
        review.setReviewReason(truncate(reason));
        review.setOriginalPermitJson(writeJson(
                snapshotMapper.toSnapshot(context.getSchedulePermit()),
                "permit review snapshot"));

        WordPermitParseResult parseResult = context.getWordPermitParseResult();
        if (parseResult != null) {
            review.setProfileId(parseResult.profileId());
            review.setProfileVersion(parseResult.profileVersion());
            review.setConfidence(parseResult.confidence());
            review.setRunnerUpMargin(parseResult.runnerUpMargin());
            review.setProfileCandidatesJson(writeJson(parseResult.candidates(), "profile candidates"));
            review.setFieldDiagnosticsJson(writeJson(parseResult.fields(), "field diagnostics"));
            review.setWarningsJson(writeJson(parseResult.warnings(), "parse warnings"));
        }
        return permitReviewRepository.save(review);
    }

    private String writeJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize " + label, exception);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 2000);
    }
}
