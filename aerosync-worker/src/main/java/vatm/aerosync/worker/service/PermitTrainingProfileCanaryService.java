package vatm.aerosync.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileCanaryCommand;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitTrainingProfileEvidenceRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;
import vatm.aerosync.worker.pipeline.LearnedPermitProfileReplayValidator;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class PermitTrainingProfileCanaryService {

    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitTrainingProfileEvidenceRepository evidenceRepository;
    private final LearnedPermitProfileReplayValidator replayValidator;
    private final PermitTrainingProfileCanaryResultService resultService;
    private final ObjectMapper objectMapper;

    public PermitTrainingProfileCanaryService(
            PermitTrainingProfileVersionRepository profileRepository,
            PermitTrainingProfileEvidenceRepository evidenceRepository,
            LearnedPermitProfileReplayValidator replayValidator,
            PermitTrainingProfileCanaryResultService resultService,
            ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.evidenceRepository = evidenceRepository;
        this.replayValidator = replayValidator;
        this.resultService = resultService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public void evaluate(PermitTrainingProfileCanaryCommand command) {
        PermitTrainingProfileVersion profile = profileRepository
                .findById(command.profileId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: "
                                + command.profileId()));
        if (!currentCanary(profile, command.definitionChecksum())) {
            return;
        }
        PermitTrainingProfileEvidence evidence = evidenceRepository
                .findByIdAndTrainingProfileId(
                        command.evidenceId(), command.profileId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit profile canary evidence not found: "
                                + command.evidenceId()));
        if (evidence.getKind() != PermitTrainingEvidenceKind.CANARY
                || evidence.getResult() != PermitTrainingEvidenceResult.PENDING) {
            return;
        }

        try {
            CompiledPermitTrainingProfile compiled = read(
                    profile.getCompiledProfileJson(),
                    CompiledPermitTrainingProfile.class,
                    "compiled profile");
            PermitTrainingDocument document = read(
                    evidence.getTrainingSource().getDocumentJson(),
                    PermitTrainingDocument.class,
                    "canary document");
            PermitReviewSnapshot expected = read(
                    evidence.getExpectedSnapshotJson(),
                    PermitReviewSnapshot.class,
                    "canary expected permit");
            LearnedPermitProfileReplayValidator.ReplayResult result =
                    replayValidator.validate(compiled, document, expected);
            resultService.complete(
                    command.profileId(),
                    command.evidenceId(),
                    command.definitionChecksum(),
                    "worker",
                    result.passed(),
                    result.errors());
        } catch (RuntimeException exception) {
            resultService.complete(
                    command.profileId(),
                    command.evidenceId(),
                    command.definitionChecksum(),
                    "worker",
                    false,
                    List.of("CANARY_REPLAY_ERROR: "
                            + safeMessage(exception)));
        }
    }

    public void markFailed(
            PermitTrainingProfileCanaryCommand command,
            String message) {
        resultService.complete(
                command.profileId(),
                command.evidenceId(),
                command.definitionChecksum(),
                "worker",
                false,
                List.of("CANARY_WORKER_ERROR: " + safeMessage(message)));
    }

    private boolean currentCanary(
            PermitTrainingProfileVersion profile,
            String definitionChecksum) {
        return profile.getStatus() == PermitTrainingProfileStatus.CANARY
                && profile.getDefinitionChecksum().equals(definitionChecksum)
                && profile.getCompiledProfileJson() != null
                && !profile.getCompiledProfileJson().isBlank();
    }

    private <T> T read(String json, Class<T> type, String label) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored " + label + " is invalid", exception);
        }
    }

    private String safeMessage(Exception exception) {
        return safeMessage(exception.getMessage());
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() <= 500
                ? message
                : message.substring(0, 500);
    }
}
