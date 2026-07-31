package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitTrainingCandidateResponse;
import vatm.aerosync.common.entity.PermitReview;
import vatm.aerosync.common.entity.PermitTrainingCandidate;
import vatm.aerosync.common.enums.PermitReviewStatus;
import vatm.aerosync.common.enums.PermitTrainingStatus;
import vatm.aerosync.common.repository.PermitTrainingCandidateRepository;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public PermitTrainingCandidateService(
            PermitTrainingCandidateRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void captureFromApprovedReview(PermitReview review) {
        if (review.getStatus() != PermitReviewStatus.APPROVED
                || review.getId() == null
                || review.getProfileId() == null
                || review.getProfileVersion() == null) {
            return;
        }
        List<TrainingDiagnostic> diagnostics =
                candidateDiagnostics(review);
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

    private List<TrainingDiagnostic> candidateDiagnostics(PermitReview review) {
        List<TrainingDiagnostic> diagnostics =
                new ArrayList<>(readDiagnostics(review.getFieldDiagnosticsJson()));
        for (TrainingWarning warning : readWarnings(review.getWarningsJson())) {
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
    public PermitTrainingCandidateResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public PermitTrainingCandidateResponse approve(
            Long id,
            String comment,
            String actor) {
        PermitTrainingCandidate candidate = findForUpdate(id);
        requirePending(candidate);
        List<PermitTrainingCandidate> conflicts =
                repository.findByProfileIdAndProfileVersionAndCanonicalAliasAndStatus(
                        candidate.getProfileId(),
                        candidate.getProfileVersion(),
                        candidate.getCanonicalAlias(),
                        PermitTrainingStatus.APPROVED);
        boolean conflictingField = conflicts.stream()
                .anyMatch(active -> !active.getSemanticField()
                        .equals(candidate.getSemanticField()));
        if (conflictingField) {
            throw new IllegalStateException(
                    "This alias is already approved for another semantic field");
        }
        candidate.setStatus(PermitTrainingStatus.APPROVED);
        candidate.setDecisionComment(blankToNull(comment));
        candidate.setDecidedBy(actor);
        candidate.setDecidedAt(LocalDateTime.now());
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
        requirePending(candidate);
        candidate.setStatus(PermitTrainingStatus.REJECTED);
        candidate.setDecisionComment(reason.trim());
        candidate.setDecidedBy(actor);
        candidate.setDecidedAt(LocalDateTime.now());
        return toResponse(repository.save(candidate));
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
                .constructCollectionType(List.class, TrainingDiagnostic.class);
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

    private void requirePending(PermitTrainingCandidate candidate) {
        if (candidate.getStatus() != PermitTrainingStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending training candidates can be decided; current status is "
                            + candidate.getStatus());
        }
    }

    private PermitTrainingCandidateResponse toResponse(
            PermitTrainingCandidate candidate) {
        return new PermitTrainingCandidateResponse(
                candidate.getId(),
                candidate.getSourceReview().getId(),
                candidate.getStatus(),
                candidate.getProfileId(),
                candidate.getProfileVersion(),
                candidate.getSemanticField(),
                candidate.getAliasValue(),
                candidate.getMatchMethod(),
                candidate.getConfidence(),
                candidate.getProposedBy(),
                candidate.getDecisionComment(),
                candidate.getDecidedBy(),
                candidate.getDecidedAt(),
                candidate.getVersion(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt());
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
