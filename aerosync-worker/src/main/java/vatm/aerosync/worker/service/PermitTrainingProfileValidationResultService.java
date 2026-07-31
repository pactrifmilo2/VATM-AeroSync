package vatm.aerosync.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.entity.PermitTrainingProfileEvent;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitTrainingProfileEventRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileEvidenceRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class PermitTrainingProfileValidationResultService {

    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitTrainingProfileEvidenceRepository evidenceRepository;
    private final PermitTrainingProfileEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public PermitTrainingProfileValidationResultService(
            PermitTrainingProfileVersionRepository profileRepository,
            PermitTrainingProfileEvidenceRepository evidenceRepository,
            PermitTrainingProfileEventRepository eventRepository,
            ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            Long profileId,
            String definitionChecksum,
            String actor,
            String compiledJson,
            List<PermitTrainingProfileValidationService.ValidationItem> items) {
        PermitTrainingProfileVersion profile = findForUpdate(profileId);
        if (!currentRequest(profile, definitionChecksum)) {
            return;
        }
        Map<Long, PermitTrainingProfileValidationService.ValidationItem>
                resultsById = items.stream().collect(
                        java.util.stream.Collectors.toMap(
                                PermitTrainingProfileValidationService
                                        .ValidationItem::evidenceId,
                                item -> item));
        List<PermitTrainingProfileEvidence> evidence = evidenceRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profileId);
        LocalDateTime now = LocalDateTime.now();
        for (PermitTrainingProfileEvidence item : evidence) {
            PermitTrainingProfileValidationService.ValidationItem result =
                    resultsById.get(item.getId());
            if (result == null) {
                continue;
            }
            item.setResult(result.passed()
                    ? PermitTrainingEvidenceResult.PASSED
                    : PermitTrainingEvidenceResult.FAILED);
            item.setActor(actor(actor));
            item.setEvaluatedAt(now);
            item.setDetail(truncate(
                    result.passed()
                            ? "Compiled profile replay passed"
                            : "Compiled profile replay failed: "
                                    + String.join("; ", result.errors()),
                    2000));
        }
        evidenceRepository.saveAll(evidence);

        long passed = items.stream()
                .filter(PermitTrainingProfileValidationService
                        .ValidationItem::passed)
                .count();
        long failed = items.size() - passed;
        boolean allPassed = !items.isEmpty() && failed == 0;
        profile.setCompiledProfileJson(compiledJson);
        profile.setStatus(allPassed
                ? PermitTrainingProfileStatus.CANARY
                : PermitTrainingProfileStatus.NEEDS_REVISION);
        profile.setCanarySuccessCount(0);
        profile.setLastError(allPassed
                ? null
                : "Replay validation failed for " + failed
                        + " of " + items.size() + " training sources");
        profileRepository.save(profile);
        record(
                profile,
                allPassed ? "VALIDATION_PASSED" : "VALIDATION_FAILED",
                actor,
                Map.of(
                        "corpusSize", items.size(),
                        "passedCount", passed,
                        "failedCount", failed,
                        "definitionChecksum", definitionChecksum));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            Long profileId,
            String definitionChecksum,
            String actor,
            String message) {
        PermitTrainingProfileVersion profile = findForUpdate(profileId);
        if (!currentRequest(profile, definitionChecksum)) {
            return;
        }
        profile.setCompiledProfileJson(null);
        profile.setStatus(PermitTrainingProfileStatus.NEEDS_REVISION);
        profile.setCanarySuccessCount(0);
        profile.setLastError(truncate(message, 2000));
        profileRepository.save(profile);
        record(
                profile,
                "VALIDATION_FAILED",
                actor,
                Map.of(
                        "definitionChecksum", definitionChecksum,
                        "error", truncate(message, 1000)));
    }

    private boolean currentRequest(
            PermitTrainingProfileVersion profile,
            String definitionChecksum) {
        return profile.getStatus() == PermitTrainingProfileStatus.VALIDATING
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
        event.setEventDetail(write(detail));
        eventRepository.save(event);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize profile validation event", exception);
        }
    }

    private String actor(String value) {
        if (value == null || value.isBlank()) {
            return "system";
        }
        return value.length() <= 100 ? value : value.substring(0, 100);
    }

    private String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }
}
