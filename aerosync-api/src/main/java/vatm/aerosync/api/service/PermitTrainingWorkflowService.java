package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.config.PermitTrainingProperties;
import vatm.aerosync.api.dto.PermitTrainingProfileCanaryRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileCreateRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileDetailResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileEvidenceRequest;
import vatm.aerosync.api.dto.PermitTrainingSourceDetailResponse;
import vatm.aerosync.api.dto.PermitTrainingWorkflowRequests;
import vatm.aerosync.api.dto.PermitTrainingWorkflowResponse;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.entity.PermitTrainingProfileEvent;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitReviewRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileEventRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class PermitTrainingWorkflowService {

    private static final Set<PermitTrainingProfileStatus> RESUMABLE = Set.of(
            PermitTrainingProfileStatus.DRAFT,
            PermitTrainingProfileStatus.COLLECTING_EVIDENCE);
    private static final Set<String> REQUIRED_SCALARS = Set.of(
            "permit.sourceNumber", "permit.date", "operator.icao");
    private static final Set<String> REQUIRED_SCHEDULE = Set.of(
            "flightNumber", "effectiveFrom", "effectiveTo", "serviceDays",
            "fromAirport", "etd", "toAirport");

    private final PermitTrainingSourceService sourceService;
    private final PermitTrainingProfileService profileService;
    private final PermitTrainingProfileValidationApiService validationService;
    private final PermitTrainingProfileCanaryApiService canaryService;
    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitReviewRepository reviewRepository;
    private final PermitTrainingProfileEventRepository eventRepository;
    private final PermitTrainingLayoutFingerprint fingerprintService;
    private final PermitTrainingMappingAssistant mappingAssistant;
    private final PermitTrainingProperties properties;
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public PermitTrainingWorkflowService(
            PermitTrainingSourceService sourceService,
            PermitTrainingProfileService profileService,
            PermitTrainingProfileValidationApiService validationService,
            PermitTrainingProfileCanaryApiService canaryService,
            PermitTrainingProfileVersionRepository profileRepository,
            PermitReviewRepository reviewRepository,
            PermitTrainingProfileEventRepository eventRepository,
            PermitTrainingLayoutFingerprint fingerprintService,
            PermitTrainingMappingAssistant mappingAssistant,
            PermitTrainingProperties properties) {
        this.sourceService = sourceService;
        this.profileService = profileService;
        this.validationService = validationService;
        this.canaryService = canaryService;
        this.profileRepository = profileRepository;
        this.reviewRepository = reviewRepository;
        this.eventRepository = eventRepository;
        this.fingerprintService = fingerprintService;
        this.mappingAssistant = mappingAssistant;
        this.properties = properties;
    }

    @Transactional
    public PermitTrainingWorkflowResponse start(
            Long sourceId,
            String actor) {
        requireEnabled();
        PermitTrainingSourceDetailResponse source = sourceService.get(sourceId);
        if (!source.retained()) {
            source = sourceService.retain(sourceId);
        }
        if (source.document() == null) {
            throw new IllegalStateException(
                    "This Word document does not contain structured training data");
        }
        String fingerprint = fingerprintService.fingerprint(source.document());
        PermitTrainingProfileVersion resumable = profileRepository
                .findFirstByLayoutFingerprintAndStatusInOrderByUpdatedAtDesc(
                        fingerprint, RESUMABLE)
                .orElse(null);
        if (resumable != null) {
            return view(resumable.getId());
        }

        String profileKey = "learned-" + fingerprint.substring(0, 12);
        String displayName = displayName(source.originalFileName());
        String baseProfileId = source.confidence() != null
                && source.confidence() >= 0.90
                ? source.profileId() : null;
        PermitTrainingProfileDetailResponse created = profileService.create(
                new PermitTrainingProfileCreateRequest(
                        profileKey,
                        displayName,
                        "caav-english",
                        baseProfileId,
                        sourceId),
                actor);
        PermitTrainingProfileVersion entity = profileRepository
                .findByIdForUpdate(created.id())
                .orElseThrow();
        entity.setLayoutFingerprint(fingerprint);
        profileRepository.saveAndFlush(entity);

        PermitReviewSnapshot expected = linkedPermit(source.syncJobId());
        if (expected != null) {
            PermitTrainingMappingAssistant.Assistance assistance =
                    mappingAssistant.suggest(
                            source.document(), expected, displayName,
                            "caav-english");
            PermitTrainingProfileDetailResponse updated =
                    profileService.updateDefinition(
                            created.id(), entity.getVersion(),
                            assistance.definition(), actor);
            profileService.attachEvidence(
                    created.id(),
                    new PermitTrainingProfileEvidenceRequest(
                            updated.version(),
                            sourceId,
                            PermitTrainingEvidenceKind.TRAINING,
                            expected),
                    actor);
        }
        return view(created.id());
    }

    @Transactional(readOnly = true)
    public PermitTrainingWorkflowResponse view(Long profileId) {
        requireEnabled();
        PermitTrainingProfileDetailResponse profile = profileService.get(profileId);
        Long sourceId = profile.evidence().isEmpty()
                ? null : profile.evidence().getFirst().sourceId();
        PermitTrainingSourceDetailResponse source = sourceId == null
                ? null : sourceService.get(sourceId);
        PermitReviewSnapshot expected = profile.evidence().stream()
                .map(item -> item.expectedPermit())
                .filter(item -> item != null)
                .findFirst()
                .orElseGet(() -> source == null
                        ? null : linkedPermit(source.syncJobId()));
        PermitTrainingMappingAssistant.Assistance assistance =
                source == null || source.document() == null
                        ? null
                        : mappingAssistant.suggest(
                                source.document(), expected,
                                profile.definition().displayName(),
                                profile.definition().family());
        List<String> unresolved = new ArrayList<>(
                unresolved(profile.definition(), expected));
        if (assistance != null && !resolutionsCurrent(profile)) {
            assistance.unresolved().stream()
                    .filter(item -> !unresolved.contains(item))
                    .forEach(unresolved::add);
        }
        List<PermitTrainingWorkflowResponse.Suggestion> suggestions =
                assistance == null ? List.of() : assistance.suggestions();
        var readiness = canaryService.readiness(profileId);
        int trainingExamples = Math.toIntExact(profile.evidence().stream()
                .filter(item -> item.kind() == PermitTrainingEvidenceKind.TRAINING)
                .filter(item -> item.expectedPermit() != null)
                .count());
        int canaryPassed = Math.toIntExact(profile.evidence().stream()
                .filter(item -> item.kind() == PermitTrainingEvidenceKind.CANARY)
                .filter(item -> item.result()
                        == PermitTrainingEvidenceResult.PASSED)
                .count());
        int canaryPending = Math.toIntExact(profile.evidence().stream()
                .filter(item -> item.kind() == PermitTrainingEvidenceKind.CANARY)
                .filter(item -> item.result()
                        == PermitTrainingEvidenceResult.PENDING)
                .count());
        int canaryFailed = Math.toIntExact(profile.evidence().stream()
                .filter(item -> item.kind() == PermitTrainingEvidenceKind.CANARY)
                .filter(item -> item.result()
                        == PermitTrainingEvidenceResult.FAILED)
                .count());
        PermitTrainingWorkflowResponse.Progress progress =
                new PermitTrainingWorkflowResponse.Progress(
                        trainingExamples, 1,
                        canaryPassed, readiness.minimumSuccesses(),
                        canaryPending, canaryFailed);
        boolean editable = profile.status() == PermitTrainingProfileStatus.DRAFT;
        boolean canValidate = (profile.status()
                == PermitTrainingProfileStatus.DRAFT
                || profile.status()
                == PermitTrainingProfileStatus.COLLECTING_EVIDENCE)
                && expected != null && unresolved.isEmpty();
        PermitTrainingWorkflowResponse.Actions actions =
                new PermitTrainingWorkflowResponse.Actions(
                        editable,
                        editable && expected != null,
                        canValidate,
                        profile.status() == PermitTrainingProfileStatus.DRAFT
                                || profile.status()
                                == PermitTrainingProfileStatus.COLLECTING_EVIDENCE
                                || profile.status()
                                == PermitTrainingProfileStatus.CANARY,
                        readiness.readyForActivationReview(),
                        profile.status() == PermitTrainingProfileStatus.ACTIVE,
                        profile.status() == PermitTrainingProfileStatus.ACTIVE);
        return new PermitTrainingWorkflowResponse(
                profile.id(), profile.profileKey(), profile.profileVersion(),
                profile.status(), profile.version(),
                step(profile.status(), expected, unresolved,
                        readiness.readyForActivationReview()),
                source, expected, suggestions, unresolved, progress,
                readiness, actions);
    }

    @Transactional
    public PermitTrainingWorkflowResponse saveExpectedPermit(
            Long profileId,
            PermitTrainingWorkflowRequests.ExpectedPermit request,
            String actor) {
        PermitTrainingProfileDetailResponse profile = profileService.get(profileId);
        PermitTrainingSourceDetailResponse source = sourceService.get(
                request.sourceId());
        if (!source.retained()) {
            source = sourceService.retain(source.id());
        }
        PermitTrainingMappingAssistant.Assistance assistance =
                mappingAssistant.suggest(
                        source.document(), request.permit(),
                        profile.definition().displayName(),
                        profile.definition().family());
        PermitTrainingProfileDefinition inferred = mergeMappings(
                profile.definition(), assistance.definition());
        PermitTrainingProfileDetailResponse updated = profileService
                .updateDefinition(
                        profileId,
                        request.expectedVersion(),
                        inferred,
                        actor);
        profileService.attachEvidence(
                profileId,
                new PermitTrainingProfileEvidenceRequest(
                        updated.version(),
                        request.sourceId(),
                        PermitTrainingEvidenceKind.TRAINING,
                        request.permit()),
                actor);
        return view(profileId);
    }

    @Transactional
    public PermitTrainingWorkflowResponse saveResolutions(
            Long profileId,
            PermitTrainingWorkflowRequests.Resolutions request,
            String actor) {
        PermitTrainingProfileDetailResponse profile = profileService.get(profileId);
        Map<String, PermitTrainingProfileDefinition.FieldMapping> fields =
                new LinkedHashMap<>();
        profile.definition().fields().forEach(field ->
                fields.put(field.semanticField(), field));
        request.fields().forEach(field -> fields.put(
                field.semanticField(),
                new PermitTrainingProfileDefinition.FieldMapping(
                        field.semanticField(), field.source(), field.cellId(),
                        field.selectedText(), field.confirmedValue(),
                        field.required())));

        List<PermitTrainingProfileDefinition.TableMapping> tables =
                new ArrayList<>(profile.definition().tables());
        if (request.schedule() != null) {
            replaceTable(tables, new PermitTrainingProfileDefinition.TableMapping(
                    PermitTrainingProfileDefinition.TableRole.SCHEDULE,
                    request.schedule().tableIndex(),
                    request.schedule().dataStartRowIndex(),
                    request.schedule().columns()));
        }
        if (request.route() != null) {
            replaceTable(tables, new PermitTrainingProfileDefinition.TableMapping(
                    PermitTrainingProfileDefinition.TableRole.ROUTE,
                    request.route().tableIndex(),
                    request.route().dataStartRowIndex(),
                    request.route().columns()));
        }
        if (request.aircraft() != null) {
            replaceTable(tables, new PermitTrainingProfileDefinition.TableMapping(
                    PermitTrainingProfileDefinition.TableRole.AIRCRAFT,
                    request.aircraft().tableIndex(),
                    request.aircraft().dataStartRowIndex(),
                    request.aircraft().columns()));
        }
        PermitTrainingProfileDefinition definition =
                new PermitTrainingProfileDefinition(
                        1,
                        profile.definition().displayName(),
                        profile.definition().family(),
                        List.copyOf(fields.values()),
                        tables,
                        profile.definition().options());
        PermitTrainingProfileDetailResponse updated = profileService.updateDefinition(
                profileId, request.expectedVersion(), definition, actor);
        validateResolvedMappings(updated.definition(), profile, request);
        recordResolutionEvent(profileId, actor, request);
        return view(profileId);
    }

    @Transactional
    public PermitTrainingWorkflowResponse addExample(
            Long profileId,
            PermitTrainingWorkflowRequests.Example request,
            String actor) {
        PermitTrainingProfileDetailResponse profile = profileService.get(profileId);
        PermitTrainingSourceDetailResponse source = sourceService.get(request.sourceId());
        if (!source.retained()) {
            sourceService.retain(request.sourceId());
        }
        if (profile.status() == PermitTrainingProfileStatus.CANARY) {
            canaryService.requestCanary(
                    profileId,
                    new PermitTrainingProfileCanaryRequest(
                            request.expectedVersion(),
                            request.sourceId(),
                            request.permit()),
                    actor);
        } else {
            profileService.attachEvidence(
                    profileId,
                    new PermitTrainingProfileEvidenceRequest(
                            request.expectedVersion(),
                            request.sourceId(),
                            PermitTrainingEvidenceKind.TRAINING,
                            request.permit()),
                    actor);
        }
        return view(profileId);
    }

    @Transactional
    public PermitTrainingWorkflowResponse validate(
            Long profileId,
            PermitTrainingWorkflowRequests.Validate request,
            String actor) {
        PermitTrainingWorkflowResponse current = view(profileId);
        if (!current.unresolved().isEmpty()) {
            throw new IllegalStateException(
                    "Resolve the highlighted fields before testing this format: "
                            + current.unresolved());
        }
        PermitTrainingProfileDetailResponse profile = profileService.get(profileId);
        long version = request.expectedVersion();
        if (profile.status() == PermitTrainingProfileStatus.DRAFT) {
            PermitTrainingProfileDetailResponse confirmed =
                    profileService.confirmMapping(profileId, version, actor);
            version = confirmed.version();
        }
        validationService.requestValidation(profileId, version, actor);
        return view(profileId);
    }

    private PermitTrainingProfileDefinition mergeMappings(
            PermitTrainingProfileDefinition existing,
            PermitTrainingProfileDefinition inferred) {
        if (existing == null
                || existing.fields().isEmpty() && existing.tables().isEmpty()) {
            return inferred;
        }
        Map<String, PermitTrainingProfileDefinition.FieldMapping> fields =
                new LinkedHashMap<>();
        inferred.fields().forEach(field ->
                fields.put(field.semanticField(), field));
        existing.fields().forEach(field ->
                fields.put(field.semanticField(), field));

        Map<PermitTrainingProfileDefinition.TableRole,
                PermitTrainingProfileDefinition.TableMapping> tables =
                new LinkedHashMap<>();
        inferred.tables().forEach(table -> tables.put(table.role(), table));
        existing.tables().forEach(table -> {
            PermitTrainingProfileDefinition.TableMapping suggested =
                    tables.get(table.role());
            if (suggested == null) {
                tables.put(table.role(), table);
                return;
            }
            Map<String, String> columns = new LinkedHashMap<>(
                    suggested.columns());
            columns.putAll(table.columns());
            tables.put(table.role(), new PermitTrainingProfileDefinition.TableMapping(
                    table.role(), table.tableIndex(),
                    table.dataStartRowIndex(), columns));
        });
        return new PermitTrainingProfileDefinition(
                inferred.schemaVersion(),
                inferred.displayName(),
                inferred.family(),
                List.copyOf(fields.values()),
                List.copyOf(tables.values()),
                inferred.options());
    }

    private void replaceTable(
            List<PermitTrainingProfileDefinition.TableMapping> tables,
            PermitTrainingProfileDefinition.TableMapping replacement) {
        tables.removeIf(table -> table.role() == replacement.role());
        tables.add(replacement);
    }

    private boolean resolutionsCurrent(
            PermitTrainingProfileDetailResponse profile) {
        long latestResolution = profile.history().stream()
                .filter(event -> "ASSISTED_RESOLUTIONS_SAVED".equals(
                        event.action()))
                .mapToLong(event -> event.id() == null ? 0L : event.id())
                .max().orElse(0L);
        long latestExpected = profile.history().stream()
                .filter(event -> "EVIDENCE_ATTACHED".equals(event.action())
                        || "EVIDENCE_UPDATED".equals(event.action()))
                .mapToLong(event -> event.id() == null ? 0L : event.id())
                .max().orElse(0L);
        return latestResolution > latestExpected;
    }

    private void validateResolvedMappings(
            PermitTrainingProfileDefinition definition,
            PermitTrainingProfileDetailResponse previous,
            PermitTrainingWorkflowRequests.Resolutions request) {
        Long sourceId = previous.evidence().isEmpty()
                ? null : previous.evidence().getFirst().sourceId();
        PermitReviewSnapshot expected = previous.evidence().stream()
                .map(item -> item.expectedPermit())
                .filter(item -> item != null)
                .findFirst().orElse(null);
        if (sourceId == null || expected == null) {
            return;
        }
        PermitTrainingSourceDetailResponse source = sourceService.get(sourceId);
        PermitTrainingMappingAssistant.Assistance assistance =
                mappingAssistant.suggest(
                        source.document(), expected,
                        definition.displayName(), definition.family());
        Set<String> submittedFields = request.fields().stream()
                .map(PermitTrainingWorkflowRequests.FieldResolution::semanticField)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> submittedColumns = request.schedule() == null
                ? Set.of() : request.schedule().columns().keySet();
        Set<String> submittedRouteColumns = request.route() == null
                ? Set.of() : request.route().columns().keySet();
        Set<String> submittedAircraftColumns = request.aircraft() == null
                ? Set.of() : request.aircraft().columns().keySet();
        List<String> missing = assistance.unresolved().stream()
                .filter(item -> {
                    String semantic = item.replaceFirst("^confirm\\.", "");
                    if (semantic.startsWith("schedule.")) {
                        return !submittedColumns.contains(
                                semantic.substring("schedule.".length()));
                    }
                    if (semantic.startsWith("route.")) {
                        String column = semantic.substring("route.".length());
                        return !submittedRouteColumns.contains(column)
                                && !submittedColumns.contains(column);
                    }
                    if (semantic.startsWith("aircraft.")) {
                        String column = semantic.substring("aircraft.".length());
                        return !submittedAircraftColumns.contains(column)
                                && !submittedColumns.contains(column);
                    }
                    return !submittedFields.contains(semantic);
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Confirm every highlighted field before continuing: "
                            + missing);
        }
    }

    private void recordResolutionEvent(
            Long profileId,
            String actor,
            PermitTrainingWorkflowRequests.Resolutions request) {
        PermitTrainingProfileVersion entity = profileRepository.findById(profileId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: " + profileId));
        PermitTrainingProfileEvent event = new PermitTrainingProfileEvent();
        event.setTrainingProfile(entity);
        event.setAction("ASSISTED_RESOLUTIONS_SAVED");
        event.setActor(actor == null || actor.isBlank()
                ? "unknown" : actor.substring(0, Math.min(actor.length(), 100)));
        try {
            event.setEventDetail(objectMapper.writeValueAsString(Map.of(
                    "fields", request.fields().stream()
                            .map(PermitTrainingWorkflowRequests.FieldResolution::semanticField)
                            .toList(),
                    "scheduleColumns", request.schedule() == null
                            ? Set.of() : request.schedule().columns().keySet(),
                    "routeColumns", request.route() == null
                            ? Set.of() : request.route().columns().keySet(),
                    "aircraftColumns", request.aircraft() == null
                            ? Set.of() : request.aircraft().columns().keySet())));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not save assisted resolution history", exception);
        }
        eventRepository.saveAndFlush(event);
    }

    private List<String> unresolved(
            PermitTrainingProfileDefinition definition,
            PermitReviewSnapshot expected) {
        if (expected == null) {
            return List.of("CORRECT_PERMIT_REQUIRED");
        }
        List<String> unresolved = new ArrayList<>();
        Set<String> fields = definition.fields().stream()
                .map(PermitTrainingProfileDefinition.FieldMapping::semanticField)
                .collect(java.util.stream.Collectors.toSet());
        REQUIRED_SCALARS.stream().filter(field -> !fields.contains(field))
                .forEach(unresolved::add);
        Set<String> columns = definition.tables().stream()
                .filter(table -> table.role()
                        == PermitTrainingProfileDefinition.TableRole.SCHEDULE)
                .findFirst()
                .map(table -> table.columns().keySet())
                .orElse(Set.of());
        REQUIRED_SCHEDULE.stream().filter(column -> !columns.contains(column))
                .map(column -> "schedule." + column)
                .forEach(unresolved::add);
        if (definition.options() == null) {
            unresolved.add("BUSINESS_DEFAULTS_REQUIRED");
        }
        return unresolved;
    }

    private PermitReviewSnapshot linkedPermit(Long syncJobId) {
        return reviewRepository.findByPermitImportSyncJobId(syncJobId)
                .map(review -> firstSnapshot(
                        review.getPublishedPermitJson(),
                        review.getCorrectedPermitJson(),
                        review.getOriginalPermitJson()))
                .orElse(null);
    }

    private PermitReviewSnapshot firstSnapshot(String... candidates) {
        for (String json : candidates) {
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                return objectMapper.readValue(json, PermitReviewSnapshot.class);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                        "Stored permit review snapshot is invalid", exception);
            }
        }
        return null;
    }

    private String step(
            PermitTrainingProfileStatus status,
            PermitReviewSnapshot expected,
            List<String> unresolved,
            boolean ready) {
        if (status == PermitTrainingProfileStatus.ACTIVE) {
            return "ACTIVE";
        }
        if (ready) {
            return "ACTIVATE";
        }
        if (status == PermitTrainingProfileStatus.CANARY
                || status == PermitTrainingProfileStatus.VALIDATING) {
            return "TEST_MORE_DOCUMENTS";
        }
        if (expected == null) {
            return "CHECK_RESULT";
        }
        if (!unresolved.isEmpty()) {
            return "RESOLVE_FIELDS";
        }
        return "VALIDATE";
    }

    private String displayName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "Learned permit format";
        }
        return fileName.replaceFirst("(?i)\\.docx?$", "")
                + " learned format";
    }

    private void requireEnabled() {
        if (!properties.isAssistedUiEnabled()) {
            throw new IllegalStateException(
                    "Assisted permit training is disabled");
        }
    }
}
