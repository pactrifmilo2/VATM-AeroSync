package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.PermitTrainingProfileCanaryReadinessResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileDetailResponse;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.entity.PermitTrainingProfileEvent;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitTrainingProfileEventRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class PermitTrainingProfileLifecycleService {

    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitTrainingProfileEventRepository eventRepository;
    private final PermitTrainingProfileCanaryApiService canaryService;
    private final PermitTrainingProfileService profileService;
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public PermitTrainingProfileLifecycleService(
            PermitTrainingProfileVersionRepository profileRepository,
            PermitTrainingProfileEventRepository eventRepository,
            PermitTrainingProfileCanaryApiService canaryService,
            PermitTrainingProfileService profileService) {
        this.profileRepository = profileRepository;
        this.eventRepository = eventRepository;
        this.canaryService = canaryService;
        this.profileService = profileService;
    }

    @Transactional
    public PermitTrainingProfileDetailResponse activate(
            Long profileId,
            long expectedVersion,
            boolean acknowledgement,
            String actor) {
        if (!acknowledgement) {
            throw new IllegalArgumentException(
                    "Confirm that learned permits will still require operator review");
        }
        PermitTrainingProfileVersion selected = locked(profileId);
        requireVersion(selected, expectedVersion);
        if (selected.getStatus() != PermitTrainingProfileStatus.CANARY) {
            throw new IllegalStateException(
                    "Only a profile that passed unseen tests can be activated");
        }
        PermitTrainingProfileCanaryReadinessResponse readiness =
                canaryService.readiness(profileId);
        if (!readiness.readyForActivationReview()) {
            throw new IllegalStateException(
                    "This format is not ready to activate: "
                            + readiness.blockers());
        }
        CompiledPermitTrainingProfile compiled = compiled(selected);
        if (!selected.getDefinitionChecksum().equals(
                compiled.definitionChecksum())) {
            throw new IllegalStateException(
                    "The compiled format is older than the current mapping; test it again");
        }
        if (compiled.options() == null || !compiled.options().reviewOnly()) {
            throw new IllegalStateException(
                    "Learned formats must require operator review");
        }

        List<PermitTrainingProfileVersion> versions = profileRepository
                .findByProfileKeyForUpdate(selected.getProfileKey());
        for (PermitTrainingProfileVersion version : versions) {
            if (version.getStatus() == PermitTrainingProfileStatus.ACTIVE
                    && !version.getId().equals(selected.getId())) {
                version.setStatus(PermitTrainingProfileStatus.DISABLED);
                version.touch();
                profileRepository.save(version);
                record(version, "SUPERSEDED", actor,
                        Map.of("replacementProfileId", selected.getId()));
            }
        }
        selected.setStatus(PermitTrainingProfileStatus.ACTIVE);
        selected.setLastError(null);
        selected.touch();
        selected = profileRepository.saveAndFlush(selected);
        record(selected, "ACTIVATED", actor, Map.of(
                "profileKey", selected.getProfileKey(),
                "profileVersion", selected.getProfileVersion(),
                "definitionChecksum", selected.getDefinitionChecksum(),
                "reviewOnly", true));
        return profileService.get(selected.getId());
    }

    @Transactional
    public PermitTrainingProfileDetailResponse disable(
            Long profileId,
            long expectedVersion,
            String reason,
            String actor) {
        PermitTrainingProfileVersion profile = locked(profileId);
        requireVersion(profile, expectedVersion);
        if (profile.getStatus() != PermitTrainingProfileStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only an active learned format can be disabled");
        }
        profile.setStatus(PermitTrainingProfileStatus.DISABLED);
        profile.setLastError(trim(reason, 2000));
        profile.touch();
        profile = profileRepository.saveAndFlush(profile);
        record(profile, "DISABLED", actor, Map.of("reason", trim(reason, 1000)));
        return profileService.get(profile.getId());
    }

    @Transactional
    public PermitTrainingProfileDetailResponse rollback(
            Long activeProfileId,
            long expectedVersion,
            Long targetProfileId,
            String reason,
            String actor) {
        PermitTrainingProfileVersion active = locked(activeProfileId);
        requireVersion(active, expectedVersion);
        if (active.getStatus() != PermitTrainingProfileStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Rollback must start from the active learned format");
        }
        List<PermitTrainingProfileVersion> versions = profileRepository
                .findByProfileKeyForUpdate(active.getProfileKey());
        PermitTrainingProfileVersion target = versions.stream()
                .filter(item -> item.getId().equals(targetProfileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Rollback target must be a version of the same format"));
        if (target.getStatus() != PermitTrainingProfileStatus.DISABLED
                || target.getCompiledProfileJson() == null
                || target.getCompiledProfileJson().isBlank()
                || !eventRepository.existsByTrainingProfileIdAndAction(
                        target.getId(), "ACTIVATED")) {
            throw new IllegalStateException(
                    "Rollback target must be a previously active unchanged version");
        }
        CompiledPermitTrainingProfile compiled = compiled(target);
        if (!target.getDefinitionChecksum().equals(
                compiled.definitionChecksum())) {
            throw new IllegalStateException(
                    "Rollback target mapping no longer matches its compiled format");
        }
        active.setStatus(PermitTrainingProfileStatus.DISABLED);
        active.touch();
        profileRepository.save(active);
        target.setStatus(PermitTrainingProfileStatus.ACTIVE);
        target.setLastError(null);
        target.touch();
        target = profileRepository.saveAndFlush(target);
        record(active, "ROLLED_BACK_FROM", actor, Map.of(
                "targetProfileId", target.getId(),
                "reason", trim(reason, 1000)));
        record(target, "ROLLED_BACK_TO", actor, Map.of(
                "previousProfileId", active.getId(),
                "reason", trim(reason, 1000)));
        return profileService.get(target.getId());
    }

    private PermitTrainingProfileVersion locked(Long id) {
        return profileRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: " + id));
    }

    private void requireVersion(
            PermitTrainingProfileVersion profile,
            long expectedVersion) {
        if (profile.getVersion() != expectedVersion) {
            throw new IllegalStateException(
                    "The learned format changed; reload before continuing");
        }
    }

    private CompiledPermitTrainingProfile compiled(
            PermitTrainingProfileVersion profile) {
        if (profile.getCompiledProfileJson() == null
                || profile.getCompiledProfileJson().isBlank()) {
            throw new IllegalStateException(
                    "A tested compiled format is required");
        }
        try {
            return objectMapper.readValue(
                    profile.getCompiledProfileJson(),
                    CompiledPermitTrainingProfile.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored compiled format is invalid", exception);
        }
    }

    private void record(
            PermitTrainingProfileVersion profile,
            String action,
            String actor,
            Map<String, ?> detail) {
        PermitTrainingProfileEvent event = new PermitTrainingProfileEvent();
        event.setTrainingProfile(profile);
        event.setAction(action);
        event.setActor(trim(actor, 100));
        try {
            event.setEventDetail(objectMapper.writeValueAsString(detail));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not record learned-format history", exception);
        }
        eventRepository.saveAndFlush(event);
    }

    private String trim(String value, int length) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= length
                ? trimmed : trimmed.substring(0, length);
    }
}
