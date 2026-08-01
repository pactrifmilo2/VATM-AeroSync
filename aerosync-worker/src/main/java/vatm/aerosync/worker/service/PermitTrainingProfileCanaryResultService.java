package vatm.aerosync.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.entity.PermitTrainingProfileEvent;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
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
public class PermitTrainingProfileCanaryResultService {

    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitTrainingProfileEvidenceRepository evidenceRepository;
    private final PermitTrainingProfileEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public PermitTrainingProfileCanaryResultService(
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
            Long evidenceId,
            String definitionChecksum,
            String actor,
            boolean passed,
            List<String> errors) {
        PermitTrainingProfileVersion profile = findForUpdate(profileId);
        if (!currentCanary(profile, definitionChecksum)) {
            return;
        }
        PermitTrainingProfileEvidence evidence = evidenceRepository
                .findByIdAndTrainingProfileId(evidenceId, profileId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit profile canary evidence not found: "
                                + evidenceId));
        if (evidence.getKind() != PermitTrainingEvidenceKind.CANARY
                || evidence.getResult() != PermitTrainingEvidenceResult.PENDING) {
            return;
        }
        List<String> safeErrors = errors == null ? List.of() : errors;
        evidence.setResult(passed
                ? PermitTrainingEvidenceResult.PASSED
                : PermitTrainingEvidenceResult.FAILED);
        evidence.setActor(actor(actor));
        evidence.setEvaluatedAt(LocalDateTime.now());
        evidence.setDetail(truncate(
                passed
                        ? "Unseen canary replay passed"
                        : "Unseen canary replay failed: "
                                + String.join("; ", safeErrors),
                2000));
        evidenceRepository.save(evidence);

        List<PermitTrainingProfileEvidence> allEvidence = evidenceRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profileId);
        if (!passed) {
            LocalDateTime now = LocalDateTime.now();
            allEvidence.stream()
                    .filter(item -> item.getKind()
                            == PermitTrainingEvidenceKind.CANARY)
                    .filter(item -> item.getResult()
                            == PermitTrainingEvidenceResult.PENDING)
                    .filter(item -> !item.getId().equals(evidenceId))
                    .forEach(item -> {
                        item.setResult(PermitTrainingEvidenceResult.REJECTED);
                        item.setActor("system");
                        item.setEvaluatedAt(now);
                        item.setDetail(
                                "Skipped because another canary failed");
                    });
            evidenceRepository.saveAll(allEvidence);
        }
        int passedCount = Math.toIntExact(allEvidence.stream()
                .filter(item -> item.getKind()
                        == PermitTrainingEvidenceKind.CANARY)
                .filter(item -> item.getResult()
                        == PermitTrainingEvidenceResult.PASSED)
                .count());
        profile.setCanarySuccessCount(passedCount);
        profile.setStatus(passed
                ? PermitTrainingProfileStatus.CANARY
                : PermitTrainingProfileStatus.NEEDS_REVISION);
        profile.setLastError(passed
                ? null
                : truncate("Canary replay failed: "
                        + String.join("; ", safeErrors), 2000));
        profileRepository.save(profile);
        record(
                profile,
                passed ? "CANARY_PASSED" : "CANARY_FAILED",
                actor,
                Map.of(
                        "evidenceId", evidenceId,
                        "sourceId", evidence.getTrainingSource().getId(),
                        "canarySuccessCount", passedCount,
                        "definitionChecksum", definitionChecksum,
                        "errors", safeErrors));
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
        try {
            event.setEventDetail(objectMapper.writeValueAsString(detail));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize canary event", exception);
        }
        eventRepository.save(event);
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
