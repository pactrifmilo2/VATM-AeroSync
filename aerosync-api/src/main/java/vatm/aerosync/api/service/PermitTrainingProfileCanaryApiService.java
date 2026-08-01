package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.config.PermitTrainingProperties;
import vatm.aerosync.api.dto.PermitTrainingProfileCanaryReadinessResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileCanaryRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileDetailResponse;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingProfileCanaryCommand;
import vatm.aerosync.common.entity.PermitTrainingProfileEvent;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitTrainingProfileEventRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileEvidenceRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class PermitTrainingProfileCanaryApiService {

    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitTrainingProfileEvidenceRepository evidenceRepository;
    private final PermitTrainingProfileEventRepository eventRepository;
    private final PermitTrainingSourceRepository sourceRepository;
    private final PermitTrainingProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final PermitTrainingProfileService profileService;
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public PermitTrainingProfileCanaryApiService(
            PermitTrainingProfileVersionRepository profileRepository,
            PermitTrainingProfileEvidenceRepository evidenceRepository,
            PermitTrainingProfileEventRepository eventRepository,
            PermitTrainingSourceRepository sourceRepository,
            PermitTrainingProperties properties,
            ApplicationEventPublisher eventPublisher,
            PermitTrainingProfileService profileService) {
        this.profileRepository = profileRepository;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.sourceRepository = sourceRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.profileService = profileService;
    }

    @Transactional
    public PermitTrainingProfileDetailResponse requestCanary(
            Long profileId,
            PermitTrainingProfileCanaryRequest request,
            String actor) {
        PermitTrainingProfileVersion profile = findForUpdate(profileId);
        requireCanaryProfile(profile);
        requireVersion(profile, request.expectedVersion());
        PermitTrainingSource source = requireRetainedSource(
                request.sourceId());
        List<PermitTrainingProfileEvidence> existing = evidenceRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profileId);
        requireUnseenSource(source, existing);
        validateExpectedPermit(request.expectedPermit());

        PermitTrainingProfileEvidence evidence =
                new PermitTrainingProfileEvidence();
        evidence.setTrainingProfile(profile);
        evidence.setTrainingSource(source);
        evidence.setKind(PermitTrainingEvidenceKind.CANARY);
        evidence.setResult(PermitTrainingEvidenceResult.PENDING);
        evidence.setExpectedSnapshotJson(writeJson(
                request.expectedPermit(), "canary expected permit"));
        evidence.setActor(actor(actor));
        evidence.setDetail("Unseen canary replay queued");
        evidence = evidenceRepository.saveAndFlush(evidence);

        profile.touch();
        profile.setLastError(null);
        profile = profileRepository.saveAndFlush(profile);
        record(
                profile,
                "CANARY_REQUESTED",
                actor,
                Map.of(
                        "evidenceId", evidence.getId(),
                        "sourceId", source.getId(),
                        "sourceHash", source.getSourceHash(),
                        "definitionChecksum", profile.getDefinitionChecksum()));

        eventPublisher.publishEvent(new PermitTrainingProfileCanaryCommand(
                profile.getId(),
                evidence.getId(),
                profile.getDefinitionChecksum(),
                actor(actor),
                LocalDateTime.now()));
        return profileService.get(profile.getId());
    }

    @Transactional
    public void markQueueFailed(
            PermitTrainingProfileCanaryCommand command,
            RuntimeException exception) {
        PermitTrainingProfileVersion profile = findForUpdate(
                command.profileId());
        if (!currentCanary(profile, command.definitionChecksum())) {
            return;
        }
        PermitTrainingProfileEvidence evidence = evidenceRepository
                .findByIdAndTrainingProfileId(
                        command.evidenceId(), command.profileId())
                .orElse(null);
        if (evidence == null
                || evidence.getKind() != PermitTrainingEvidenceKind.CANARY
                || evidence.getResult() != PermitTrainingEvidenceResult.PENDING) {
            return;
        }
        Long sourceId = evidence.getTrainingSource().getId();
        evidenceRepository.delete(evidence);
        evidenceRepository.flush();
        profile.setLastError(truncate(
                "Could not queue canary evaluation: "
                        + safeMessage(exception),
                2000));
        profile.touch();
        profileRepository.saveAndFlush(profile);
        record(
                profile,
                "CANARY_QUEUE_FAILED",
                "system",
                Map.of(
                        "evidenceId", command.evidenceId(),
                        "sourceId", sourceId,
                        "error", profile.getLastError()));
    }

    @Transactional(readOnly = true)
    public PermitTrainingProfileCanaryReadinessResponse readiness(
            Long profileId) {
        PermitTrainingProfileVersion profile = profileRepository
                .findById(profileId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: " + profileId));
        List<PermitTrainingProfileEvidence> allEvidence = evidenceRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profileId);
        List<PermitTrainingProfileEvidence> canaries = allEvidence.stream()
                .filter(item -> item.getKind()
                        == PermitTrainingEvidenceKind.CANARY)
                .toList();
        int trainingPassed = Math.toIntExact(allEvidence.stream()
                .filter(item -> item.getKind()
                        == PermitTrainingEvidenceKind.TRAINING)
                .filter(item -> item.getResult()
                        == PermitTrainingEvidenceResult.PASSED)
                .count());
        int passed = count(canaries, PermitTrainingEvidenceResult.PASSED);
        int failed = count(canaries, PermitTrainingEvidenceResult.FAILED);
        int pending = count(canaries, PermitTrainingEvidenceResult.PENDING);
        int minimum = minimumCanarySuccesses();
        List<String> blockers = new ArrayList<>();
        if (profile.getStatus() != PermitTrainingProfileStatus.CANARY) {
            blockers.add("PROFILE_NOT_IN_CANARY");
        }
        if (profile.getCompiledProfileJson() == null
                || profile.getCompiledProfileJson().isBlank()) {
            blockers.add("COMPILED_PROFILE_REQUIRED");
        }
        if (trainingPassed < 1) {
            blockers.add("TRAINING_EXAMPLE_REQUIRED");
        }
        if (failed > 0) {
            blockers.add("CANARY_FAILURE_REQUIRES_REVISION");
        }
        if (pending > 0) {
            blockers.add("CANARY_EVALUATION_PENDING");
        }
        if (passed < minimum) {
            blockers.add("MINIMUM_CANARY_SUCCESSES_REQUIRED");
        }
        return new PermitTrainingProfileCanaryReadinessResponse(
                profileId,
                profile.getStatus(),
                minimum,
                passed,
                failed,
                pending,
                blockers.isEmpty(),
                blockers);
    }

    private int count(
            List<PermitTrainingProfileEvidence> evidence,
            PermitTrainingEvidenceResult result) {
        return Math.toIntExact(evidence.stream()
                .filter(item -> item.getResult() == result)
                .count());
    }

    private int minimumCanarySuccesses() {
        return Math.max(1, properties.getMinimumCanarySuccesses());
    }

    private void requireCanaryProfile(
            PermitTrainingProfileVersion profile) {
        if (profile.getStatus() != PermitTrainingProfileStatus.CANARY) {
            throw new IllegalStateException(
                    "Canary evidence can be evaluated only after corpus replay passes");
        }
        if (profile.getCompiledProfileJson() == null
                || profile.getCompiledProfileJson().isBlank()) {
            throw new IllegalStateException(
                    "A compiled profile preview is required for canary evaluation");
        }
    }

    private void requireVersion(
            PermitTrainingProfileVersion profile,
            long expectedVersion) {
        if (profile.getVersion() != expectedVersion) {
            throw new IllegalStateException(
                    "The training profile changed; reload it before adding a canary");
        }
    }

    private PermitTrainingSource requireRetainedSource(Long sourceId) {
        PermitTrainingSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training source not found: " + sourceId));
        if (source.getRetainedAt() == null
                || source.getCorpusPath() == null
                || source.getCorpusPath().isBlank()
                || source.getDocumentJson() == null
                || source.getDocumentJson().isBlank()) {
            throw new IllegalStateException(
                    "Retain the structured source before using it as a canary");
        }
        return source;
    }

    private void requireUnseenSource(
            PermitTrainingSource source,
            List<PermitTrainingProfileEvidence> existing) {
        String hash = source.getSourceHash().toLowerCase(Locale.ROOT);
        boolean alreadySeen = existing.stream()
                .map(PermitTrainingProfileEvidence::getTrainingSource)
                .anyMatch(item -> item.getId().equals(source.getId())
                        || item.getSourceHash().equalsIgnoreCase(hash));
        if (alreadySeen) {
            throw new IllegalStateException(
                    "A canary source must be unseen by this profile version");
        }
    }

    private void validateExpectedPermit(PermitReviewSnapshot snapshot) {
        if (snapshot == null
                || snapshot.normalizedPermitId() == null
                || snapshot.normalizedPermitId().isBlank()
                || snapshot.normalizedPermitId().length() > 100) {
            throw new IllegalArgumentException(
                    "A valid normalizedPermitId is required");
        }
        if (snapshot.permitDate() == null) {
            throw new IllegalArgumentException("permitDate is required");
        }
        if (snapshot.operatorId() == null
                || !snapshot.operatorId().matches("^[A-Z0-9]{3}$")) {
            throw new IllegalArgumentException(
                    "operatorId must be a three-character ICAO code");
        }
        if (snapshot.permitType() == null
                || snapshot.permitType().isBlank()
                || snapshot.flightType() == null
                || snapshot.flightType().isBlank()) {
            throw new IllegalArgumentException(
                    "permitType and flightType are required");
        }
        if (snapshot.flights().isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one schedule flight is required");
        }
    }

    private boolean currentCanary(
            PermitTrainingProfileVersion profile,
            String definitionChecksum) {
        return profile.getStatus() == PermitTrainingProfileStatus.CANARY
                && profile.getDefinitionChecksum().equals(definitionChecksum);
    }

    private PermitTrainingProfileVersion findForUpdate(Long id) {
        return profileRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: " + id));
    }

    private void record(
            PermitTrainingProfileVersion profile,
            String action,
            String actor,
            Map<String, ?> detail) {
        PermitTrainingProfileEvent event = new PermitTrainingProfileEvent();
        event.setTrainingProfile(profile);
        event.setAction(action);
        event.setActor(actor(actor));
        event.setEventDetail(writeJson(detail, "canary event"));
        eventRepository.saveAndFlush(event);
    }

    private String writeJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize " + label, exception);
        }
    }

    private String actor(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated actor is required");
        }
        return value.length() <= 100 ? value : value.substring(0, 100);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum
                ? value
                : value.substring(0, maximum);
    }
}
