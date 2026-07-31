package vatm.aerosync.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingValidationCommand;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;
import vatm.aerosync.worker.model.WordPermitParseResult;
import vatm.aerosync.worker.pipeline.DocxSchedulePermitParser;
import vatm.aerosync.worker.pipeline.PermitTextNormalizer;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
public class PermitTrainingValidationService {

    private final PermitTrainingCandidateRepository candidateRepository;
    private final FileRecordRepository fileRecordRepository;
    private final DocxSchedulePermitParser parser;
    private final PermitReviewSnapshotMapper snapshotMapper;
    private final PermitTrainingValidationResultService resultService;
    private final ObjectMapper objectMapper;

    public PermitTrainingValidationService(
            PermitTrainingCandidateRepository candidateRepository,
            FileRecordRepository fileRecordRepository,
            DocxSchedulePermitParser parser,
            PermitReviewSnapshotMapper snapshotMapper,
            PermitTrainingValidationResultService resultService,
            ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.fileRecordRepository = fileRecordRepository;
        this.parser = parser;
        this.snapshotMapper = snapshotMapper;
        this.resultService = resultService;
        this.objectMapper = objectMapper;
    }

    public void validate(PermitTrainingValidationCommand command) {
        PermitTrainingCandidate candidate = candidateRepository
                .findById(command.candidateId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training candidate not found: "
                                + command.candidateId()));
        if (candidate.getValidationStatus()
                != PermitTrainingValidationStatus.RUNNING) {
            return;
        }
        List<PermitTrainingCandidate> group =
                candidateRepository.findValidationGroup(
                                candidate.getProfileId(),
                                candidate.getProfileVersion(),
                                candidate.getSemanticField(),
                                candidate.getCanonicalAlias())
                        .stream()
                        .filter(item -> item.getStatus()
                                != PermitTrainingStatus.REJECTED)
                        .toList();
        Map<Long, PermitTrainingCandidate> evidenceByReview =
                new LinkedHashMap<>();
        group.forEach(item -> evidenceByReview.putIfAbsent(
                item.getSourceReview().getId(), item));
        List<Long> jobIds = evidenceByReview.values().stream()
                .map(PermitTrainingCandidate::getSourceReview)
                .map(PermitReview::getPermitImport)
                .map(permitImport -> permitImport.getSyncJob().getId())
                .distinct()
                .toList();
        Map<Long, List<FileRecord>> filesByJob =
                fileRecordRepository.findBySyncJobIdIn(jobIds).stream()
                        .filter(this::wordDocument)
                        .collect(java.util.stream.Collectors.groupingBy(
                                file -> file.getSyncJob().getId()));

        List<ValidationItem> items = evidenceByReview.values().stream()
                .map(evidence -> validateEvidence(
                        candidate,
                        evidence,
                        filesByJob.getOrDefault(
                                evidence.getSourceReview()
                                        .getPermitImport()
                                        .getSyncJob()
                                        .getId(),
                                List.of())))
                .toList();
        int passedCount = (int) items.stream()
                .filter(ValidationItem::passed)
                .count();
        int failedCount = items.size() - passedCount;
        resultService.complete(
                candidate.getId(),
                "worker",
                items.size(),
                passedCount,
                failedCount,
                report(items));
    }

    public void markFailed(
            PermitTrainingValidationCommand command,
            String message) {
        resultService.fail(
                command.candidateId(),
                "worker",
                "Corpus replay failed unexpectedly: " + message);
    }

    private ValidationItem validateEvidence(
            PermitTrainingCandidate preview,
            PermitTrainingCandidate evidence,
            List<FileRecord> files) {
        PermitReview review = evidence.getSourceReview();
        FileRecord file = files.stream()
                .sorted(Comparator.comparing(
                        FileRecord::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElse(null);
        if (file == null) {
            return failed(review, null, null, "SOURCE_FILE_NOT_RETAINED");
        }
        Path path;
        try {
            path = Path.of(file.getStoredPath()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            return failed(
                    review,
                    file,
                    file.getOriginalFileName(),
                    "SOURCE_PATH_INVALID");
        }
        if (!Files.isRegularFile(path)) {
            return failed(
                    review,
                    file,
                    file.getOriginalFileName(),
                    "SOURCE_FILE_NOT_RETAINED");
        }
        try {
            WordPermitParseResult actual =
                    parser.parseWithTrainingCandidate(
                            path,
                            file.getOriginalFileName(),
                            preview);
            if (!preview.getProfileId().equals(actual.profileId())
                    || preview.getProfileVersion()
                    != actual.profileVersion()) {
                return failed(
                        review,
                        file,
                        file.getOriginalFileName(),
                        "PROFILE_CHANGED");
            }
            boolean aliasUsed = actual.fields().stream()
                    .filter(field -> preview.getSemanticField()
                            .equals(field.field()))
                    .filter(field -> "DECLARED_ALIAS".equals(
                            field.method()))
                    .map(field -> field.observedValue())
                    .filter(Objects::nonNull)
                    .map(PermitTextNormalizer::canonicalHeader)
                    .anyMatch(preview.getCanonicalAlias()::equals);
            if (!aliasUsed) {
                return failed(
                        review,
                        file,
                        file.getOriginalFileName(),
                        "ALIAS_NOT_USED_FOR_EXPECTED_FIELD");
            }
            String expectedJson =
                    review.getCorrectedPermitJson() == null
                            || review.getCorrectedPermitJson().isBlank()
                            ? review.getOriginalPermitJson()
                            : review.getCorrectedPermitJson();
            PermitReviewSnapshot expected = objectMapper.readValue(
                    expectedJson,
                    PermitReviewSnapshot.class);
            PermitReviewSnapshot reparsed =
                    snapshotMapper.toSnapshot(actual.permit());
            if (!expected.equals(reparsed)) {
                return failed(
                        review,
                        file,
                        file.getOriginalFileName(),
                        "EXTRACTED_PERMIT_CHANGED");
            }
            return new ValidationItem(
                    review.getId(),
                    file.getId(),
                    file.getOriginalFileName(),
                    true,
                    "PASSED");
        } catch (RuntimeException | JsonProcessingException exception) {
            return failed(
                    review,
                    file,
                    file.getOriginalFileName(),
                    "REPLAY_ERROR: " + safeMessage(exception));
        }
    }

    private boolean wordDocument(FileRecord file) {
        String name = file.getOriginalFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".doc") || lower.endsWith(".docx");
    }

    private ValidationItem failed(
            PermitReview review,
            FileRecord file,
            String fileName,
            String detail) {
        return new ValidationItem(
                review.getId(),
                file == null ? null : file.getId(),
                fileName,
                false,
                detail);
    }

    private String report(List<ValidationItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException exception) {
            return items.toString();
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record ValidationItem(
            Long sourceReviewId,
            Long fileRecordId,
            String fileName,
            boolean passed,
            String detail
    ) {
    }
}
