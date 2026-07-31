package vatm.aerosync.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.dto.PermitTrainingProfileValidationCommand;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitTrainingProfileEvidenceRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;
import vatm.aerosync.worker.pipeline.LearnedPermitProfileCompiler;
import vatm.aerosync.worker.pipeline.LearnedPermitProfileReplayValidator;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class PermitTrainingProfileValidationService {

    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitTrainingProfileEvidenceRepository evidenceRepository;
    private final LearnedPermitProfileCompiler compiler;
    private final LearnedPermitProfileReplayValidator replayValidator;
    private final PermitTrainingProfileValidationResultService resultService;
    private final ObjectMapper objectMapper;

    public PermitTrainingProfileValidationService(
            PermitTrainingProfileVersionRepository profileRepository,
            PermitTrainingProfileEvidenceRepository evidenceRepository,
            LearnedPermitProfileCompiler compiler,
            LearnedPermitProfileReplayValidator replayValidator,
            PermitTrainingProfileValidationResultService resultService,
            ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.evidenceRepository = evidenceRepository;
        this.compiler = compiler;
        this.replayValidator = replayValidator;
        this.resultService = resultService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public void validate(PermitTrainingProfileValidationCommand command) {
        PermitTrainingProfileVersion profile = profileRepository
                .findById(command.profileId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: "
                                + command.profileId()));
        if (profile.getStatus() != PermitTrainingProfileStatus.VALIDATING
                || !profile.getDefinitionChecksum()
                .equals(command.definitionChecksum())) {
            return;
        }
        List<PermitTrainingProfileEvidence> evidence = evidenceRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profile.getId())
                .stream()
                .filter(item -> item.getExpectedSnapshotJson() != null
                        && !item.getExpectedSnapshotJson().isBlank())
                .toList();
        if (evidence.isEmpty()) {
            throw new IllegalStateException(
                    "No corrected training evidence is available");
        }

        PermitTrainingProfileDefinition definition = read(
                profile.getDefinitionJson(),
                PermitTrainingProfileDefinition.class,
                "profile definition");
        PermitTrainingDocument primaryDocument = read(
                evidence.getFirst().getTrainingSource().getDocumentJson(),
                PermitTrainingDocument.class,
                "primary training document");
        CompiledPermitTrainingProfile compiled = compiler.compile(
                profile, definition, primaryDocument);
        String compiledJson = write(compiled, "compiled profile");

        List<ValidationItem> items = evidence.stream()
                .map(item -> validateEvidence(compiled, item))
                .toList();
        resultService.complete(
                profile.getId(),
                command.definitionChecksum(),
                "worker",
                compiledJson,
                items);
    }

    public void markFailed(
            PermitTrainingProfileValidationCommand command,
            String message) {
        resultService.fail(
                command.profileId(),
                command.definitionChecksum(),
                "worker",
                "Learned-profile validation failed unexpectedly: "
                        + safeMessage(message));
    }

    private ValidationItem validateEvidence(
            CompiledPermitTrainingProfile compiled,
            PermitTrainingProfileEvidence evidence) {
        try {
            PermitTrainingDocument document = read(
                    evidence.getTrainingSource().getDocumentJson(),
                    PermitTrainingDocument.class,
                    "training document");
            PermitReviewSnapshot expected = read(
                    evidence.getExpectedSnapshotJson(),
                    PermitReviewSnapshot.class,
                    "expected permit");
            LearnedPermitProfileReplayValidator.ReplayResult result =
                    replayValidator.validate(compiled, document, expected);
            return new ValidationItem(
                    evidence.getId(),
                    evidence.getTrainingSource().getId(),
                    result.passed(),
                    result.errors());
        } catch (RuntimeException exception) {
            return new ValidationItem(
                    evidence.getId(),
                    evidence.getTrainingSource().getId(),
                    false,
                    List.of("REPLAY_ERROR: "
                            + safeMessage(exception.getMessage())));
        }
    }

    private <T> T read(String json, Class<T> type, String label) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored " + label + " is invalid", exception);
        }
    }

    private String write(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize " + label, exception);
        }
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() <= 500
                ? message
                : message.substring(0, 500);
    }

    public record ValidationItem(
            Long evidenceId,
            Long sourceId,
            boolean passed,
            List<String> errors
    ) {
        public ValidationItem {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
