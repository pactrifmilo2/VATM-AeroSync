package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.PermitTrainingProfileDetailResponse;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.dto.PermitTrainingProfileValidationCommand;
import vatm.aerosync.common.entity.PermitTrainingProfileEvent;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitTrainingProfileEventRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileEvidenceRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class PermitTrainingProfileValidationApiService {

    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitTrainingProfileEvidenceRepository evidenceRepository;
    private final PermitTrainingProfileEventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PermitTrainingProfileService profileService;
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public PermitTrainingProfileValidationApiService(
            PermitTrainingProfileVersionRepository profileRepository,
            PermitTrainingProfileEvidenceRepository evidenceRepository,
            PermitTrainingProfileEventRepository eventRepository,
            ApplicationEventPublisher eventPublisher,
            PermitTrainingProfileService profileService) {
        this.profileRepository = profileRepository;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
        this.profileService = profileService;
    }

    @Transactional
    public PermitTrainingProfileDetailResponse requestValidation(
            Long profileId,
            long expectedVersion,
            String actor) {
        PermitTrainingProfileVersion profile = findForUpdate(profileId);
        if (profile.getStatus()
                != PermitTrainingProfileStatus.COLLECTING_EVIDENCE) {
            throw new IllegalStateException(
                    "Only a confirmed profile collecting evidence can be validated");
        }
        if (profile.getVersion() != expectedVersion) {
            throw new IllegalStateException(
                    "The training profile changed; reload it before validating");
        }
        long corrected = evidenceRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profileId)
                .stream()
                .filter(item -> item.getResult()
                        == PermitTrainingEvidenceResult.CORRECTED)
                .filter(item -> item.getExpectedSnapshotJson() != null
                        && !item.getExpectedSnapshotJson().isBlank())
                .count();
        if (corrected < 1) {
            throw new IllegalStateException(
                    "At least one corrected training source is required");
        }

        profile.setStatus(PermitTrainingProfileStatus.VALIDATING);
        profile.setCompiledProfileJson(null);
        profile.setCanarySuccessCount(0);
        profile.setLastError(null);
        profile = profileRepository.saveAndFlush(profile);
        record(
                profile,
                "VALIDATION_REQUESTED",
                actor,
                Map.of(
                        "evidenceCount", corrected,
                        "definitionChecksum", profile.getDefinitionChecksum()));

        PermitTrainingProfileValidationCommand command =
                new PermitTrainingProfileValidationCommand(
                        profile.getId(),
                        profile.getDefinitionChecksum(),
                        actor(actor),
                        LocalDateTime.now());
        eventPublisher.publishEvent(command);
        return profileService.get(profile.getId());
    }

    @Transactional
    public void markQueueFailed(
            PermitTrainingProfileValidationCommand command,
            RuntimeException exception) {
        PermitTrainingProfileVersion profile = findForUpdate(
                command.profileId());
        if (profile.getStatus() != PermitTrainingProfileStatus.VALIDATING
                || !profile.getDefinitionChecksum()
                .equals(command.definitionChecksum())) {
            return;
        }
        profile.setStatus(PermitTrainingProfileStatus.COLLECTING_EVIDENCE);
        profile.setLastError(truncate(
                "Could not queue profile validation: "
                        + safeMessage(exception),
                2000));
        profileRepository.saveAndFlush(profile);
        record(
                profile,
                "VALIDATION_QUEUE_FAILED",
                "system",
                Map.of("error", profile.getLastError()));
    }

    @Transactional(readOnly = true)
    public CompiledPermitTrainingProfile compiled(Long profileId) {
        PermitTrainingProfileVersion profile = profileRepository
                .findById(profileId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: " + profileId));
        if (profile.getCompiledProfileJson() == null
                || profile.getCompiledProfileJson().isBlank()) {
            throw new IllegalStateException(
                    "This profile has not produced a compiled preview");
        }
        try {
            return objectMapper.readValue(
                    profile.getCompiledProfileJson(),
                    CompiledPermitTrainingProfile.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored compiled profile is invalid", exception);
        }
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
                    "Could not serialize profile validation event", exception);
        }
        eventRepository.saveAndFlush(event);
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
