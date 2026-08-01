package vatm.aerosync.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vatm.aerosync.common.dto.CompiledPermitTrainingProfile;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;
import vatm.aerosync.worker.config.WorkerProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** A short-lived, fail-closed cache of active learned extraction profiles. */
@Component
public class ActiveLearnedPermitProfileCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ActiveLearnedPermitProfileCatalog.class);

    private final PermitTrainingProfileVersionRepository repository;
    private final WorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private volatile Snapshot snapshot = new Snapshot(Instant.EPOCH, List.of());

    @Autowired
    public ActiveLearnedPermitProfileCatalog(
            PermitTrainingProfileVersionRepository repository,
            WorkerProperties properties,
            ObjectMapper objectMapper) {
        this(repository, properties, objectMapper, Clock.systemUTC());
    }

    ActiveLearnedPermitProfileCatalog(
            PermitTrainingProfileVersionRepository repository,
            WorkerProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<ActiveProfile> activeProfiles() {
        if (!properties.isLearnedProfilesRuntimeEnabled()) {
            return List.of();
        }
        Instant now = clock.instant();
        Snapshot current = snapshot;
        if (now.isBefore(current.expiresAt())) {
            return current.profiles();
        }
        synchronized (this) {
            current = snapshot;
            if (now.isBefore(current.expiresAt())) {
                return current.profiles();
            }
            long seconds = Math.max(1L,
                    properties.getLearnedProfileCacheSeconds());
            try {
                List<ActiveProfile> loaded = repository
                        .findAllByStatus(PermitTrainingProfileStatus.ACTIVE)
                        .stream()
                        .map(this::read)
                        .filter(profile -> profile != null)
                        .toList();
                snapshot = new Snapshot(now.plusSeconds(seconds), loaded);
                return loaded;
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not refresh active learned permit profiles; "
                        + "continuing with the last safe cache", exception);
                snapshot = new Snapshot(now.plusSeconds(1), current.profiles());
                return current.profiles();
            }
        }
    }

    public synchronized void invalidate() {
        snapshot = new Snapshot(Instant.EPOCH, List.of());
    }

    private ActiveProfile read(PermitTrainingProfileVersion entity) {
        if (entity.getLayoutFingerprint() == null
                || entity.getLayoutFingerprint().isBlank()
                || entity.getCompiledProfileJson() == null
                || entity.getCompiledProfileJson().isBlank()) {
            LOGGER.warn("Ignoring active learned profile {} with incomplete runtime data",
                    entity.getId());
            return null;
        }
        try {
            CompiledPermitTrainingProfile compiled = objectMapper.readValue(
                    entity.getCompiledProfileJson(),
                    CompiledPermitTrainingProfile.class);
            if (!entity.getDefinitionChecksum().equals(
                    compiled.definitionChecksum())
                    || compiled.options() == null
                    || !compiled.options().reviewOnly()) {
                LOGGER.warn("Ignoring unsafe active learned profile {}",
                        entity.getId());
                return null;
            }
            return new ActiveProfile(
                    entity.getId(), entity.getLayoutFingerprint(), compiled);
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Ignoring unreadable active learned profile {}",
                    entity.getId(), exception);
            return null;
        }
    }

    public record ActiveProfile(
            Long id,
            String layoutFingerprint,
            CompiledPermitTrainingProfile compiled
    ) {
    }

    private record Snapshot(
            Instant expiresAt,
            List<ActiveProfile> profiles
    ) {
        private Snapshot {
            profiles = List.copyOf(profiles);
        }
    }
}
