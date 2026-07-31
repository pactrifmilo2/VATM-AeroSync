package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.config.PermitTrainingProperties;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitTrainingCandidateResponse;
import vatm.aerosync.api.dto.PermitTrainingDecisionResponse;
import vatm.aerosync.api.dto.PermitTrainingGroupResponse;
import vatm.aerosync.api.dto.PermitTrainingPreflightResponse;
import vatm.aerosync.common.dto.PermitTrainingValidationCommand;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.entity.PermitTrainingDecision;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.enums.PermitTrainingAction;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.enums.PermitTrainingValidationStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;
import vatm.aerosync.common.repository.PermitTrainingDecisionRepository;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PermitTrainingCandidateService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> PROMOTABLE_METHODS =
            Set.of("SHARED_ALIAS", "FUZZY_ALIAS");
    private static final Pattern ALIAS_WARNING = Pattern.compile(
            "^(?<field>(?:schedule|route|aircraft)\\.[A-Za-z][A-Za-z0-9]*) "
                    + "(?:matched shared alias|fuzzily matched header) "
                    + "'(?<alias>.+)'$");

    private final PermitTrainingCandidateRepository repository;
    private final PermitTrainingDecisionRepository decisionRepository;
    private final PermitTrainingProperties properties;
    private final PermitTrainingValidationPublisher validationPublisher;
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public PermitTrainingCandidateService(
            PermitTrainingCandidateRepository repository,
            PermitTrainingDecisionRepository decisionRepository,
            PermitTrainingProperties properties,
            PermitTrainingValidationPublisher validationPublisher) {
        this.repository = repository;
        this.decisionRepository = decisionRepository;
        this.properties = properties;
        this.validationPublisher = validationPublisher;
    }

    @Transactional
    public void captureFromApprovedReview(PermitReview review) {
        if (review.getStatus() != PermitReviewStatus.APPROVED
                || review.getId() == null
                || review.getProfileId() == null
                || review.getProfileVersion() == null) {
            return;
        }
        List<TrainingDiagnostic> diagnostics = candidateDiagnostics(review);
        Set<String> captured = new HashSet<>();
        for (TrainingDiagnostic diagnostic : diagnostics) {
            if (!promotable(diagnostic)) {
                continue;
            }
            String alias = clean(diagnostic.observedValue());
            String canonical = canonicalHeader(alias);
            if (alias.isBlank() || alias.length() > 500 || canonical.isBlank()) {
                continue;
            }
            String key = diagnostic.field() + "|" + canonical;
            if (!captured.add(key)) {
                continue;
            }
            if (repository.existsBySourceReviewIdAndSemanticFieldAndCanonicalAlias(
                    review.getId(), diagnostic.field(), canonical)) {
                continue;
            }
            PermitTrainingCandidate candidate = new PermitTrainingCandidate();
            candidate.setSourceReview(review);
            candidate.setStatus(PermitTrainingStatus.PENDING);
            candidate.setProfileId(review.getProfileId());
            candidate.setProfileVersion(review.getProfileVersion());
            candidate.setSemanticField(diagnostic.field());
            candidate.setAliasValue(alias);
            candidate.setCanonicalAlias(canonical);
            candidate.setMatchMethod(diagnostic.method());
            candidate.setConfidence(diagnostic.confidence());
            candidate.setProposedBy(review.getApprovedBy());
            repository.save(candidate);
        }
    }

    @Transactional(readOnly = true)
    public PagedResponse<PermitTrainingCandidateResponse> list(
            PermitTrainingStatus status,
            String profileId,
            int page,
            int size) {
        validatePage(page, size);
        String normalizedProfile = blankToNull(profileId);
        PageRequest request = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<PermitTrainingCandidate> candidates;
        if (status != null && normalizedProfile != null) {
            candidates = repository.findByStatusAndProfileId(
                    status, normalizedProfile, request);
        } else if (status != null) {
            candidates = repository.findByStatus(status, request);
        } else if (normalizedProfile != null) {
            candidates = repository.findByProfileId(normalizedProfile, request);
        } else {
            candidates = repository.findAll(request);
        }
        return PagedResponse.from(candidates.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public List<PermitTrainingGroupResponse> groups(String profileId) {
        String normalizedProfile = blankToNull(profileId);
        List<PermitTrainingCandidate> candidates = normalizedProfile == null
                ? repository.findAll()
                : repository.findAllByProfileId(normalizedProfile);
        Map<GroupKey, List<PermitTrainingCandidate>> grouped =
                candidates.stream().collect(Collectors.groupingBy(
                        GroupKey::from,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return grouped.values().stream()
                .map(this::toGroupResponse)
                .sorted(Comparator
                        .comparing(
                                PermitTrainingGroupResponse::latestEvidenceAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PermitTrainingGroupResponse::profileId)
                        .thenComparing(PermitTrainingGroupResponse::semanticField))
                .toList();
    }

    @Transactional(readOnly = true)
    public PermitTrainingCandidateResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public PermitTrainingPreflightResponse preflight(Long id) {
        PermitTrainingCandidate candidate = find(id);
        return preflight(candidate, exactGroup(candidate), activeAliases(candidate));
    }

    @Transactional(readOnly = true)
    public List<PermitTrainingDecisionResponse> history(Long id) {
        find(id);
        return decisionRepository.findByCandidateIdOrderByCreatedAtAscIdAsc(id)
                .stream()
                .map(this::toDecisionResponse)
                .toList();
    }

    @Transactional
    public PermitTrainingCandidateResponse requestValidation(
            Long id,
            String actor) {
        PermitTrainingCandidate candidate = lockAliasGroup(id);
        requireStatus(
                candidate,
                Set.of(PermitTrainingStatus.PENDING,
                        PermitTrainingStatus.DISABLED),
                "Only pending or disabled training candidates can be validated");
        if (candidate.getValidationStatus()
                == PermitTrainingValidationStatus.RUNNING) {
            throw new IllegalStateException(
                    "Corpus validation is already running");
        }

        PermitTrainingPreflightResponse check = preflight(
                candidate,
                lockedExactGroup(candidate),
                lockedActiveAliases(candidate));
        List<String> validationBlockers = check.blockers().stream()
                .filter(blocker ->
                        !"CORPUS_VALIDATION_REQUIRED".equals(blocker))
                .toList();
        if (!validationBlockers.isEmpty()) {
            throw new IllegalStateException(
                    "Training validation cannot start: "
                            + String.join(", ", validationBlockers));
        }

        LocalDateTime now = LocalDateTime.now();
        candidate.setValidationStatus(PermitTrainingValidationStatus.RUNNING);
        candidate.setValidationRequestedBy(actor);
        candidate.setValidationRequestedAt(now);
        candidate.setValidationCompletedAt(null);
        candidate.setValidationCorpusSize(null);
        candidate.setValidationPassedCount(null);
        candidate.setValidationFailedCount(null);
        candidate.setValidationReport(null);
        PermitTrainingCandidate saved = repository.save(candidate);
        recordDecision(
                saved,
                PermitTrainingAction.VALIDATION_REQUESTED,
                actor,
                "Worker corpus replay requested");
        try {
            validationPublisher.publish(new PermitTrainingValidationCommand(
                    saved.getId(), actor, now));
        } catch (RuntimeException exception) {
            saved.setValidationStatus(PermitTrainingValidationStatus.FAILED);
            saved.setValidationCompletedAt(LocalDateTime.now());
            saved.setValidationCorpusSize(0);
            saved.setValidationPassedCount(0);
            saved.setValidationFailedCount(0);
            saved.setValidationReport(truncate(
                    "Could not queue worker validation: "
                            + exception.getMessage(),
                    4000));
            repository.save(saved);
            recordDecision(
                    saved,
                    PermitTrainingAction.VALIDATION_FAILED,
                    "system",
                    saved.getValidationReport());
        }
        return toResponse(saved);
    }

    @Transactional
    public PermitTrainingCandidateResponse approve(
            Long id,
            String comment,
            String actor) {
        PermitTrainingCandidate candidate = lockAliasGroup(id);
        requireStatus(
                candidate,
                Set.of(PermitTrainingStatus.PENDING),
                "Only pending training candidates can be approved");
        requireReady(candidate);
        decide(
                candidate,
                PermitTrainingStatus.APPROVED,
                comment,
                actor,
                PermitTrainingAction.APPROVED);
        return toResponse(repository.save(candidate));
    }

    @Transactional
    public PermitTrainingCandidateResponse reject(
            Long id,
            String reason,
            String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required");
        }
        PermitTrainingCandidate candidate = findForUpdate(id);
        requireStatus(
                candidate,
                Set.of(PermitTrainingStatus.PENDING),
                "Only pending training candidates can be rejected");
        decide(
                candidate,
                PermitTrainingStatus.REJECTED,
                reason,
                actor,
                PermitTrainingAction.REJECTED);
        return toResponse(repository.save(candidate));
    }

    @Transactional
    public PermitTrainingCandidateResponse disable(
            Long id,
            String reason,
            String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A disable reason is required");
        }
        PermitTrainingCandidate candidate = findForUpdate(id);
        requireStatus(
                candidate,
                Set.of(PermitTrainingStatus.APPROVED),
                "Only approved training candidates can be disabled");
        decide(
                candidate,
                PermitTrainingStatus.DISABLED,
                reason,
                actor,
                PermitTrainingAction.DISABLED);
        resetValidation(candidate);
        return toResponse(repository.save(candidate));
    }

    @Transactional
    public PermitTrainingCandidateResponse reactivate(
            Long id,
            String comment,
            String actor) {
        PermitTrainingCandidate candidate = lockAliasGroup(id);
        requireStatus(
                candidate,
                Set.of(PermitTrainingStatus.DISABLED),
                "Only disabled training candidates can be reactivated");
        requireReady(candidate);
        decide(
                candidate,
                PermitTrainingStatus.APPROVED,
                comment,
                actor,
                PermitTrainingAction.REACTIVATED);
        return toResponse(repository.save(candidate));
    }

    private void requireReady(PermitTrainingCandidate candidate) {
        PermitTrainingPreflightResponse check = preflight(
                candidate,
                lockedExactGroup(candidate),
                lockedActiveAliases(candidate));
        if (!check.ready()) {
            throw new IllegalStateException(
                    "Training candidate is not ready: "
                            + String.join(", ", check.blockers()));
        }
    }

    private PermitTrainingPreflightResponse preflight(
            PermitTrainingCandidate candidate,
            List<PermitTrainingCandidate> exactGroup,
            List<PermitTrainingCandidate> activeAliases) {
        List<PermitTrainingCandidate> evidence = exactGroup.stream()
                .filter(item -> item.getStatus()
                        != PermitTrainingStatus.REJECTED)
                .toList();
        List<Long> candidateIds = evidence.stream()
                .map(PermitTrainingCandidate::getId)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        List<Long> reviewIds = evidence.stream()
                .map(PermitTrainingCandidate::getSourceReview)
                .filter(Objects::nonNull)
                .map(PermitReview::getId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        int evidenceCount = reviewIds.size();
        boolean evidenceReady = evidenceCount >= minimumEvidence();
        List<PermitTrainingCandidate> activeSameField = activeAliases.stream()
                .filter(active -> active.getStatus()
                        == PermitTrainingStatus.APPROVED)
                .filter(active -> active.getSemanticField()
                        .equals(candidate.getSemanticField()))
                .filter(active -> !Objects.equals(
                        active.getId(), candidate.getId()))
                .toList();
        List<PermitTrainingCandidate> conflicts = activeAliases.stream()
                .filter(active -> active.getStatus()
                        == PermitTrainingStatus.APPROVED)
                .filter(active -> !active.getSemanticField()
                        .equals(candidate.getSemanticField()))
                .toList();
        boolean validationRequired = properties.isRequireCorpusValidation();
        boolean validationReady = !validationRequired
                || candidate.getValidationStatus()
                == PermitTrainingValidationStatus.PASSED;
        List<String> blockers = new ArrayList<>();
        if (candidate.getStatus() != PermitTrainingStatus.PENDING
                && candidate.getStatus() != PermitTrainingStatus.DISABLED) {
            blockers.add("STATUS_NOT_ACTIVATABLE");
        }
        if (!evidenceReady) {
            blockers.add("INSUFFICIENT_EVIDENCE");
        }
        if (!activeSameField.isEmpty()) {
            blockers.add("ALIAS_ALREADY_ACTIVE");
        }
        if (!conflicts.isEmpty()) {
            blockers.add("CROSS_FIELD_ALIAS_CONFLICT");
        }
        if (!validationReady) {
            blockers.add("CORPUS_VALIDATION_REQUIRED");
        }
        return new PermitTrainingPreflightResponse(
                candidate.getId(),
                blockers.isEmpty(),
                evidenceCount,
                minimumEvidence(),
                evidenceReady,
                validationRequired,
                candidate.getValidationStatus(),
                validationReady,
                activeSameField.stream()
                        .map(PermitTrainingCandidate::getId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null),
                candidateIds,
                reviewIds,
                conflicts.stream()
                        .map(PermitTrainingCandidate::getId)
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList(),
                List.copyOf(blockers));
    }

    private PermitTrainingCandidate lockAliasGroup(Long id) {
        PermitTrainingCandidate seed = find(id);
        List<PermitTrainingCandidate> group =
                repository.findAliasGroupForUpdate(
                        seed.getProfileId(),
                        seed.getProfileVersion(),
                        seed.getCanonicalAlias());
        return group.stream()
                .filter(candidate -> Objects.equals(candidate.getId(), id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training candidate not found: " + id));
    }

    private List<PermitTrainingCandidate> lockedExactGroup(
            PermitTrainingCandidate candidate) {
        return repository.findAliasGroupForUpdate(
                        candidate.getProfileId(),
                        candidate.getProfileVersion(),
                        candidate.getCanonicalAlias())
                .stream()
                .filter(item -> item.getSemanticField()
                        .equals(candidate.getSemanticField()))
                .toList();
    }

    private List<PermitTrainingCandidate> lockedActiveAliases(
            PermitTrainingCandidate candidate) {
        return repository.findAliasGroupForUpdate(
                candidate.getProfileId(),
                candidate.getProfileVersion(),
                candidate.getCanonicalAlias());
    }

    private List<PermitTrainingCandidate> exactGroup(
            PermitTrainingCandidate candidate) {
        return repository
                .findByProfileIdAndProfileVersionAndSemanticFieldAndCanonicalAlias(
                        candidate.getProfileId(),
                        candidate.getProfileVersion(),
                        candidate.getSemanticField(),
                        candidate.getCanonicalAlias());
    }

    private List<PermitTrainingCandidate> activeAliases(
            PermitTrainingCandidate candidate) {
        return repository
                .findByProfileIdAndProfileVersionAndCanonicalAliasAndStatus(
                        candidate.getProfileId(),
                        candidate.getProfileVersion(),
                        candidate.getCanonicalAlias(),
                        PermitTrainingStatus.APPROVED);
    }

    private void decide(
            PermitTrainingCandidate candidate,
            PermitTrainingStatus status,
            String comment,
            String actor,
            PermitTrainingAction action) {
        candidate.setStatus(status);
        candidate.setDecisionComment(blankToNull(comment));
        candidate.setDecidedBy(actor);
        candidate.setDecidedAt(LocalDateTime.now());
        recordDecision(candidate, action, actor, comment);
    }

    private void recordDecision(
            PermitTrainingCandidate candidate,
            PermitTrainingAction action,
            String actor,
            String comment) {
        PermitTrainingDecision decision = new PermitTrainingDecision();
        decision.setCandidate(candidate);
        decision.setAction(action);
        decision.setActor(actor == null || actor.isBlank() ? "system" : actor);
        decision.setComment(blankToNull(truncate(comment, 2000)));
        decisionRepository.save(decision);
    }

    private void resetValidation(PermitTrainingCandidate candidate) {
        candidate.setValidationStatus(PermitTrainingValidationStatus.NOT_RUN);
        candidate.setValidationRequestedBy(null);
        candidate.setValidationRequestedAt(null);
        candidate.setValidationCompletedAt(null);
        candidate.setValidationCorpusSize(null);
        candidate.setValidationPassedCount(null);
        candidate.setValidationFailedCount(null);
        candidate.setValidationReport(null);
    }

    private List<TrainingDiagnostic> candidateDiagnostics(PermitReview review) {
        List<TrainingDiagnostic> diagnostics =
                new ArrayList<>(readDiagnostics(
                        review.getFieldDiagnosticsJson()));
        for (TrainingWarning warning : readWarnings(
                review.getWarningsJson())) {
            String method = switch (warning.code()) {
                case "SHARED_ALIAS_USED" -> "SHARED_ALIAS";
                case "FUZZY_ALIAS_USED" -> "FUZZY_ALIAS";
                default -> null;
            };
            if (method == null || warning.message() == null) {
                continue;
            }
            Matcher matcher = ALIAS_WARNING.matcher(warning.message());
            if (!matcher.matches()) {
                continue;
            }
            String field = matcher.group("field");
            boolean alreadyObserved = diagnostics.stream()
                    .anyMatch(diagnostic -> field.equals(diagnostic.field())
                            && method.equals(diagnostic.method())
                            && diagnostic.observedValue() != null);
            if (alreadyObserved) {
                continue;
            }
            double confidence = diagnostics.stream()
                    .filter(diagnostic -> field.equals(diagnostic.field())
                            && method.equals(diagnostic.method()))
                    .mapToDouble(TrainingDiagnostic::confidence)
                    .findFirst()
                    .orElse("SHARED_ALIAS".equals(method) ? 0.95 : 0.90);
            diagnostics.add(new TrainingDiagnostic(
                    field,
                    confidence,
                    "STORED_WARNING",
                    method,
                    matcher.group("alias")));
        }
        return List.copyOf(diagnostics);
    }

    private boolean promotable(TrainingDiagnostic diagnostic) {
        return diagnostic != null
                && diagnostic.field() != null
                && diagnostic.field().matches(
                        "^(schedule|route|aircraft)\\.[A-Za-z][A-Za-z0-9]*$")
                && PROMOTABLE_METHODS.contains(diagnostic.method())
                && diagnostic.observedValue() != null;
    }

    private List<TrainingDiagnostic> readDiagnostics(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JavaType type = objectMapper.getTypeFactory()
                .constructCollectionType(
                        List.class,
                        TrainingDiagnostic.class);
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored field diagnostics are invalid", exception);
        }
    }

    private List<TrainingWarning> readWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JavaType type = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, TrainingWarning.class);
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored parse warnings are invalid", exception);
        }
    }

    private PermitTrainingCandidate find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training candidate not found: " + id));
    }

    private PermitTrainingCandidate findForUpdate(Long id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training candidate not found: " + id));
    }

    private void requireStatus(
            PermitTrainingCandidate candidate,
            Set<PermitTrainingStatus> allowed,
            String message) {
        if (!allowed.contains(candidate.getStatus())) {
            throw new IllegalStateException(
                    message + "; current status is "
                            + candidate.getStatus());
        }
    }

    private PermitTrainingCandidateResponse toResponse(
            PermitTrainingCandidate candidate) {
        int evidenceCount = evidenceCount(exactGroup(candidate));
        return new PermitTrainingCandidateResponse(
                candidate.getId(),
                candidate.getSourceReview().getId(),
                candidate.getStatus(),
                candidate.getProfileId(),
                candidate.getProfileVersion(),
                candidate.getSemanticField(),
                candidate.getAliasValue(),
                candidate.getCanonicalAlias(),
                candidate.getMatchMethod(),
                candidate.getConfidence(),
                evidenceCount,
                minimumEvidence(),
                candidate.getProposedBy(),
                candidate.getDecisionComment(),
                candidate.getDecidedBy(),
                candidate.getDecidedAt(),
                candidate.getUsageCount(),
                candidate.getLastUsedAt(),
                candidate.getValidationStatus(),
                candidate.getValidationRequestedBy(),
                candidate.getValidationRequestedAt(),
                candidate.getValidationCompletedAt(),
                candidate.getValidationCorpusSize(),
                candidate.getValidationPassedCount(),
                candidate.getValidationFailedCount(),
                candidate.getValidationReport(),
                candidate.getVersion(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt());
    }

    private PermitTrainingGroupResponse toGroupResponse(
            List<PermitTrainingCandidate> group) {
        List<PermitTrainingCandidate> ordered = group.stream()
                .sorted(Comparator
                        .comparing(
                                PermitTrainingCandidate::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(
                                PermitTrainingCandidate::getId,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        PermitTrainingCandidate latest = ordered.getFirst();
        PermitTrainingCandidate active = ordered.stream()
                .filter(candidate -> candidate.getStatus()
                        == PermitTrainingStatus.APPROVED)
                .findFirst()
                .orElse(null);
        PermitTrainingStatus groupStatus = active != null
                ? PermitTrainingStatus.APPROVED
                : ordered.stream().anyMatch(candidate -> candidate.getStatus()
                        == PermitTrainingStatus.PENDING)
                        ? PermitTrainingStatus.PENDING
                        : ordered.stream().anyMatch(candidate -> candidate.getStatus()
                                == PermitTrainingStatus.DISABLED)
                                ? PermitTrainingStatus.DISABLED
                                : PermitTrainingStatus.REJECTED;
        PermitTrainingCandidate validationSource =
                active == null ? latest : active;
        return new PermitTrainingGroupResponse(
                latest.getProfileId(),
                latest.getProfileVersion(),
                latest.getSemanticField(),
                latest.getAliasValue(),
                latest.getCanonicalAlias(),
                groupStatus,
                evidenceCount(group),
                minimumEvidence(),
                countStatus(group, PermitTrainingStatus.PENDING),
                countStatus(group, PermitTrainingStatus.APPROVED),
                countStatus(group, PermitTrainingStatus.REJECTED),
                countStatus(group, PermitTrainingStatus.DISABLED),
                group.stream()
                        .mapToDouble(PermitTrainingCandidate::getConfidence)
                        .average()
                        .orElse(0.0),
                active == null ? null : active.getId(),
                group.stream()
                        .map(PermitTrainingCandidate::getId)
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList(),
                group.stream()
                        .map(PermitTrainingCandidate::getSourceReview)
                        .filter(Objects::nonNull)
                        .map(PermitReview::getId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList(),
                group.stream()
                        .mapToLong(PermitTrainingCandidate::getUsageCount)
                        .sum(),
                group.stream()
                        .map(PermitTrainingCandidate::getLastUsedAt)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null),
                validationSource.getValidationStatus(),
                ordered.stream()
                        .map(PermitTrainingCandidate::getCreatedAt)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null));
    }

    private PermitTrainingDecisionResponse toDecisionResponse(
            PermitTrainingDecision decision) {
        return new PermitTrainingDecisionResponse(
                decision.getId(),
                decision.getCandidate().getId(),
                decision.getAction(),
                decision.getActor(),
                decision.getComment(),
                decision.getCreatedAt());
    }

    private int evidenceCount(List<PermitTrainingCandidate> group) {
        return (int) group.stream()
                .filter(candidate -> candidate.getStatus()
                        != PermitTrainingStatus.REJECTED)
                .map(PermitTrainingCandidate::getSourceReview)
                .filter(Objects::nonNull)
                .map(PermitReview::getId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private int countStatus(
            List<PermitTrainingCandidate> group,
            PermitTrainingStatus status) {
        return (int) group.stream()
                .filter(candidate -> candidate.getStatus() == status)
                .count();
    }

    private int minimumEvidence() {
        return Math.max(1, properties.getMinimumEvidence());
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "'page' must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "'size' must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private String clean(String value) {
        return value == null ? "" : value
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String canonicalHeader(String value) {
        String folded = Normalizer.normalize(
                        clean(value)
                                .replace('\u0110', 'D')
                                .replace('\u0111', 'd'),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "")
                .replaceFirst("\\d+$", "");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }

    private record GroupKey(
            String profileId,
            int profileVersion,
            String semanticField,
            String canonicalAlias
    ) {
        private static GroupKey from(PermitTrainingCandidate candidate) {
            return new GroupKey(
                    candidate.getProfileId(),
                    candidate.getProfileVersion(),
                    candidate.getSemanticField(),
                    candidate.getCanonicalAlias());
        }
    }

    private record TrainingDiagnostic(
            String field,
            double confidence,
            String source,
            String method,
            String observedValue
    ) {
    }

    private record TrainingWarning(
            String code,
            String message,
            boolean reviewRequired
    ) {
    }
}
